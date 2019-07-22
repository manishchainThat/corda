package net.corda.node.services.rpc

import co.paralleluniverse.fibers.Stack
import co.paralleluniverse.strands.Strand
import com.fasterxml.jackson.annotation.JsonAutoDetect
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility
import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonInclude.Include
import com.fasterxml.jackson.annotation.JsonValue
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.databind.*
import com.fasterxml.jackson.databind.module.SimpleModule
import com.fasterxml.jackson.databind.ser.BeanPropertyWriter
import com.fasterxml.jackson.databind.ser.BeanSerializerModifier
import net.corda.client.jackson.JacksonSupport
import net.corda.client.jackson.internal.jsonObject
import net.corda.core.context.InvocationOrigin
import net.corda.core.contracts.Attachment
import net.corda.core.contracts.ScheduledStateRef
import net.corda.core.contracts.StateRef
import net.corda.core.crypto.SecureHash
import net.corda.core.flows.FlowInfo
import net.corda.core.flows.FlowLogic
import net.corda.core.flows.FlowSession
import net.corda.core.flows.StateMachineRunId
import net.corda.core.identity.CordaX500Name
import net.corda.core.identity.Party
import net.corda.core.internal.*
import net.corda.core.node.ServiceHub
import net.corda.core.serialization.SerializeAsToken
import net.corda.core.serialization.SerializedBytes
import net.corda.core.serialization.deserialize
import net.corda.core.serialization.internal.CheckpointSerializationContext
import net.corda.core.serialization.internal.CheckpointSerializationDefaults
import net.corda.core.serialization.internal.checkpointDeserialize
import net.corda.core.utilities.NonEmptySet
import net.corda.core.utilities.ProgressTracker
import net.corda.core.utilities.contextLogger
import net.corda.node.internal.NodeStartup
import net.corda.node.services.api.CheckpointStorage
import net.corda.node.services.statemachine.*
import net.corda.nodeapi.internal.persistence.CordaPersistence
import net.corda.serialization.internal.CheckpointSerializeAsTokenContextImpl
import net.corda.serialization.internal.withTokenContext
import sun.misc.VMSupport
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset.UTC
import java.time.format.DateTimeFormatter
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class CheckpointDumper(private val checkpointStorage: CheckpointStorage, private val database: CordaPersistence, private val serviceHub: ServiceHub, val baseDirectory: Path) {
    companion object {
        private val TIME_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(UTC)
        private val log = contextLogger()
    }

    private val lock = AtomicInteger(0)

    private lateinit var checkpointSerializationContext: CheckpointSerializationContext
    private lateinit var writer: ObjectWriter

    private val isCheckpointAgentRunning by lazy {
        checkpointAgentRunning()
    }

    fun start(tokenizableServices: List<Any>) {
        checkpointSerializationContext = CheckpointSerializationDefaults.CHECKPOINT_CONTEXT.withTokenContext(
                CheckpointSerializeAsTokenContextImpl(
                        tokenizableServices,
                        CheckpointSerializationDefaults.CHECKPOINT_SERIALIZER,
                        CheckpointSerializationDefaults.CHECKPOINT_CONTEXT,
                        serviceHub
                )
        )

        val mapper = JacksonSupport.createNonRpcMapper()
        mapper.registerModule(SimpleModule().apply {
            setSerializerModifier(CheckpointDumperBeanModifier)
            addSerializer(FlowSessionImplSerializer)
            addSerializer(MapSerializer)
            addSerializer(AttachmentSerializer)
            setMixInAnnotation(FlowLogic::class.java, FlowLogicMixin::class.java)
            setMixInAnnotation(SessionId::class.java, SessionIdMixin::class.java)
        })
        val prettyPrinter = DefaultPrettyPrinter().apply {
            indentArraysWith(DefaultIndenter.SYSTEM_LINEFEED_INSTANCE)
        }
        writer = mapper.writer(prettyPrinter)
    }

    fun dump() {
        val now = serviceHub.clock.instant()
        val file = baseDirectory / NodeStartup.LOGS_DIRECTORY_NAME / "checkpoints_dump-${TIME_FORMATTER.format(now)}.zip"
        try {
            if (lock.getAndIncrement() == 0 && !file.exists()) {
                database.transaction {
                    checkpointStorage.getAllCheckpoints().use { stream ->
                        ZipOutputStream(file.outputStream()).use { zip ->
                            stream.forEach { (runId, serialisedCheckpoint) ->
                                if (isCheckpointAgentRunning)
                                    instrumentCheckpointAgent(runId)
                                val checkpoint = serialisedCheckpoint.checkpointDeserialize(context = checkpointSerializationContext)
                                val json = checkpoint.toJson(runId.uuid, now)
                                val jsonBytes = writer.writeValueAsBytes(json)
                                zip.putNextEntry(ZipEntry("${json.flowLogicClass.simpleName}-${runId.uuid}.json"))
                                zip.write(jsonBytes)
                                zip.closeEntry()
                            }
                        }
                    }
                }
            } else {
                log.info("Flow dump already in progress, skipping current call")
            }
        } finally {
            lock.decrementAndGet()
        }
    }

    private fun instrumentCheckpointAgent(checkpointId: StateMachineRunId) {
        log.info("Checkpoint agent diagnostics for checkpointId: $checkpointId")
        try {
            val checkpointHook = Class.forName("net.corda.tools.CheckpointHook").kotlin
            if (checkpointHook.objectInstance == null)
                log.info("Instantiating checkpoint agent object instance")
            val instance = checkpointHook.objectOrNewInstance()
            val checkpointIdField = instance.declaredField<UUID>(instance.javaClass, "checkpointId")
            checkpointIdField.value = checkpointId.uuid
        }
        catch (e: Exception) {
            log.error("Checkpoint agent instrumentation failed for checkpointId: $checkpointId\n. ${e.message}")
        }
    }

    private fun checkpointAgentRunning(): Boolean {
        val agentProperties = VMSupport.getAgentProperties()
        return agentProperties.values.any { value ->
            (value is String && value.contains("checkpoint-agent.jar"))
        }
    }

    private fun Checkpoint.toJson(id: UUID, now: Instant): CheckpointJson {
        val (fiber, flowLogic) = when (flowState) {
            is FlowState.Unstarted -> {
                null to flowState.frozenFlowLogic.checkpointDeserialize(context = checkpointSerializationContext)
            }
            is FlowState.Started -> {
                val fiber = flowState.frozenFiber.checkpointDeserialize(context = checkpointSerializationContext)
                fiber to fiber.logic
            }
        }

        val flowCallStack = if (fiber != null) {
            // Poke into Quasar's stack and find the object references to the sub-flows so that we can correctly get the current progress
            // step for each sub-call.
            val stackObjects = fiber.declaredField<Stack>("stack").value.declaredField<Array<*>>("dataObject").value
            subFlowStack.map { subFlow ->
                val subFlowLogic = stackObjects.find(subFlow.flowClass::isInstance) as? FlowLogic<*>
                val currentStep = subFlowLogic?.progressTracker?.currentStep
                FlowCall(subFlow.flowClass, if (currentStep == ProgressTracker.UNSTARTED) null else currentStep?.label)
            }.reversed()
        } else {
            emptyList()
        }
        return CheckpointJson(
                id,
                flowLogic.javaClass,
                flowLogic,
                flowCallStack,
                (flowState as? FlowState.Started)?.flowIORequest?.toSuspendedOn(suspendedTimestamp(), now),
                invocationContext.origin.toOrigin(),
                ourIdentity,
                sessions.mapNotNull { it.value.toActiveSession(it.key) },
                errorState as? ErrorState.Errored
        )
    }

    private fun Checkpoint.suspendedTimestamp(): Instant = invocationContext.trace.invocationId.timestamp

    @Suppress("unused")
    private class FlowCall(val flowClass: Class<*>, val progressStep: String?)

    @Suppress("unused")
    @JsonInclude(Include.NON_NULL)
    private class Origin(
            val rpc: String? = null,
            val peer: CordaX500Name? = null,
            val service: String? = null,
            val scheduled: ScheduledStateRef? = null,
            val shell: InvocationOrigin.Shell? = null
    )

    private fun InvocationOrigin.toOrigin(): Origin {
        return when (this) {
            is InvocationOrigin.RPC -> Origin(rpc = actor.id.value)
            is InvocationOrigin.Peer -> Origin(peer = party)
            is InvocationOrigin.Service -> Origin(service = serviceClassName)
            is InvocationOrigin.Scheduled -> Origin(scheduled = scheduledState)
            is InvocationOrigin.Shell -> Origin(shell = this)
        }
    }

    @Suppress("unused")
    private class CheckpointJson(
            val id: UUID,
            val flowLogicClass: Class<FlowLogic<*>>,
            val flowLogic: FlowLogic<*>,
            val flowCallStack: List<FlowCall>,
            val suspendedOn: SuspendedOn?,
            val origin: Origin,
            val ourIdentity: Party,
            val activeSessions: List<ActiveSession>,
            val errored: ErrorState.Errored?
    )

    @Suppress("unused")
    @JsonInclude(Include.NON_NULL)
    private class SuspendedOn(
            val send: List<SendJson>? = null,
            val receive: NonEmptySet<FlowSession>? = null,
            val sendAndReceive: List<SendJson>? = null,
            val waitForLedgerCommit: SecureHash? = null,
            val waitForStateConsumption: Set<StateRef>? = null,
            val getFlowInfo: NonEmptySet<FlowSession>? = null,
            val sleepTill: Instant? = null,
            val waitForSessionConfirmations: FlowIORequest.WaitForSessionConfirmations? = null,
            val customOperation: FlowIORequest.ExecuteAsyncOperation<*>? = null,
            val forceCheckpoint: FlowIORequest.ForceCheckpoint? = null
    ) {
        @JsonFormat(pattern ="yyyy-MM-dd'T'HH:mm:ss", timezone = "UTC")
        lateinit var suspendedTimestamp: Instant
        var secondsSpentWaiting: Long = 0
    }

    @Suppress("unused")
    private class SendJson(val session: FlowSession, val sentPayloadType: Class<*>, val sentPayload: Any)

    private fun FlowIORequest<*>.toSuspendedOn(suspendedTimestamp: Instant, now: Instant): SuspendedOn {
        fun Map<FlowSession, SerializedBytes<Any>>.toJson(): List<SendJson> {
            return map {
                val payload = it.value.deserialize()
                SendJson(it.key, payload.javaClass, payload)
            }
        }
        return when (this) {
            is FlowIORequest.Send -> SuspendedOn(send = sessionToMessage.toJson())
            is FlowIORequest.Receive -> SuspendedOn(receive = sessions)
            is FlowIORequest.SendAndReceive -> SuspendedOn(sendAndReceive = sessionToMessage.toJson())
            is FlowIORequest.WaitForLedgerCommit -> SuspendedOn(waitForLedgerCommit = hash)
            is FlowIORequest.GetFlowInfo -> SuspendedOn(getFlowInfo = sessions)
            is FlowIORequest.Sleep -> SuspendedOn(sleepTill = wakeUpAfter)
            is FlowIORequest.WaitForSessionConfirmations -> SuspendedOn(waitForSessionConfirmations = this)
            is FlowIORequest.ForceCheckpoint -> SuspendedOn(forceCheckpoint = this)
            is FlowIORequest.ExecuteAsyncOperation -> {
                when (operation) {
                    is WaitForStateConsumption -> SuspendedOn(waitForStateConsumption = (operation as WaitForStateConsumption).stateRefs)
                    else -> SuspendedOn(customOperation = this)
                }
            }
        }.also {
            it.suspendedTimestamp = suspendedTimestamp
            it.secondsSpentWaiting = TimeUnit.MILLISECONDS.toSeconds(Duration.between(suspendedTimestamp, now).toMillis())
        }
    }

    @Suppress("unused")
    private class ActiveSession(
            val peer: Party,
            val ourSessionId: SessionId,
            val receivedMessages: List<DataSessionMessage>,
            val errors: List<FlowError>,
            val peerFlowInfo: FlowInfo,
            val peerSessionId: SessionId?
    )

    private fun SessionState.toActiveSession(sessionId: SessionId): ActiveSession? {
        return if (this is SessionState.Initiated) {
            val peerSessionId = (initiatedState as? InitiatedSessionState.Live)?.peerSinkSessionId
            ActiveSession(peerParty, sessionId, receivedMessages, errors, peerFlowInfo, peerSessionId)
        } else {
            null
        }
    }

    @Suppress("unused")
    private interface SessionIdMixin {
        @get:JsonValue
        val toLong: Long
    }

    @JsonAutoDetect(getterVisibility = Visibility.NONE, isGetterVisibility = Visibility.NONE, fieldVisibility = Visibility.ANY)
    private interface FlowLogicMixin

    private object CheckpointDumperBeanModifier : BeanSerializerModifier() {
        override fun changeProperties(config: SerializationConfig,
                                      beanDesc: BeanDescription,
                                      beanProperties: MutableList<BeanPropertyWriter>): MutableList<BeanPropertyWriter> {
            // Remove references to any node singletons
            beanProperties.removeIf { it.type.isTypeOrSubTypeOf(SerializeAsToken::class.java) }
            if (FlowLogic::class.java.isAssignableFrom(beanDesc.beanClass)) {
                beanProperties.removeIf {
                    it.type.isTypeOrSubTypeOf(ProgressTracker::class.java) || it.name == "_stateMachine" || it.name == "deprecatedPartySessionMap"
                }
            }
            return beanProperties
        }
    }

    private object FlowSessionImplSerializer : JsonSerializer<FlowSessionImpl>() {
        override fun serialize(value: FlowSessionImpl, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.jsonObject {
                writeObjectField("peer", value.counterparty)
                writeObjectField("ourSessionId", value.sourceSessionId)
            }
        }
        override fun handledType(): Class<FlowSessionImpl> = FlowSessionImpl::class.java
    }

    private object AttachmentSerializer : JsonSerializer<Attachment>() {
        override fun serialize(value: Attachment, gen: JsonGenerator, serializers: SerializerProvider) = gen.writeObject(value.id)
        override fun handledType(): Class<Attachment> = Attachment::class.java
    }

    private object MapSerializer : JsonSerializer<Map<Any, Any>>() {
        override fun serialize(map: Map<Any, Any>, gen: JsonGenerator, serializers: SerializerProvider) {
            gen.writeStartArray(map.size)
            map.forEach { key, value ->
                gen.jsonObject {
                    writeObjectField("key", key)
                    writeObjectField("value", value)
                }
            }
            gen.writeEndArray()
        }
        override fun handledType(): Class<Map<Any, Any>> = uncheckedCast(Map::class.java)
    }
}
