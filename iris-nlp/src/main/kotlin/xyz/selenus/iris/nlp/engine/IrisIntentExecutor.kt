package xyz.selenus.iris.nlp.engine

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import xyz.selenus.iris.nlp.ParseResult
import xyz.selenus.iris.nlp.TransactionIntent
import java.math.BigDecimal

/**
 * Iris Intent Execution Engine - QuickNode Enhanced
 * 
 * Extended execution engine with:
 * - JITO MEV protection for swaps
 * - PumpFun token operations
 * - Yellowstone gRPC subscriptions
 * - Fastlane transaction priority
 * - Metis DEX aggregation
 * 
 * Solana Mobile Standard compliant with Android 2026 architecture
 */
class IrisIntentExecutor private constructor(
    private val config: IrisExecutorConfig,
    private val signer: TransactionSigner,
    private val broadcaster: IrisTransactionBroadcaster
) {
    
    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()
    
    private val _transactionHistory = MutableStateFlow<List<ExecutionRecord>>(emptyList())
    val transactionHistory: StateFlow<List<ExecutionRecord>> = _transactionHistory.asStateFlow()
    
    companion object {
        fun create(
            config: IrisExecutorConfig = IrisExecutorConfig(),
            signer: TransactionSigner,
            broadcaster: IrisTransactionBroadcaster
        ): IrisIntentExecutor {
            return IrisIntentExecutor(config, signer, broadcaster)
        }
    }
    
    /**
     * Execute a parsed intent with full lifecycle management
     */
    fun execute(parseResult: ParseResult.Success): Flow<ExecutionUpdate> = flow {
        require(parseResult.confidence >= config.minimumConfidence) {
            "Intent confidence ${parseResult.confidence} below threshold ${config.minimumConfidence}"
        }
        
        _executionState.value = ExecutionState.Preparing(parseResult.intent)
        emit(ExecutionUpdate.Started(parseResult.intent))
        
        try {
            // Step 1: Preview and validate
            val preview = generatePreview(parseResult.intent)
            emit(ExecutionUpdate.Preview(preview))
            _executionState.value = ExecutionState.AwaitingConfirmation(preview)
            
            // Step 2: Build transaction with MEV protection if needed
            val transaction = buildTransaction(parseResult.intent)
            emit(ExecutionUpdate.TransactionBuilt(transaction))
            _executionState.value = ExecutionState.AwaitingSignature(transaction)
            
            // Step 3: Sign
            val signedTx = signer.sign(transaction)
            emit(ExecutionUpdate.Signed(signedTx))
            _executionState.value = ExecutionState.Broadcasting(signedTx)
            
            // Step 4: Broadcast with appropriate method
            val signature = broadcastWithStrategy(signedTx, parseResult.intent)
            emit(ExecutionUpdate.Broadcasted(signature))
            
            // Step 5: Confirm
            val confirmation = awaitConfirmation(signature)
            emit(ExecutionUpdate.Confirmed(confirmation))
            
            // Record execution
            val record = ExecutionRecord(
                intent = parseResult.intent,
                signature = signature,
                confirmation = confirmation,
                timestamp = System.currentTimeMillis(),
                broadcastMethod = determineBroadcastMethod(parseResult.intent)
            )
            _transactionHistory.value = _transactionHistory.value + record
            _executionState.value = ExecutionState.Completed(record)
            
            emit(ExecutionUpdate.Completed(record))
            
        } catch (e: ExecutionException) {
            _executionState.value = ExecutionState.Failed(e)
            emit(ExecutionUpdate.Failed(e))
            throw e
        } finally {
            kotlinx.coroutines.delay(config.resetDelayMs)
            _executionState.value = ExecutionState.Idle
        }
    }
    
    /**
     * Execute JITO-protected bundle
     */
    fun executeJitoBundle(intents: List<ParseResult.Success>): Flow<JitoBundleUpdate> = flow {
        emit(JitoBundleUpdate.Started(intents.size))
        
        // Build all transactions
        val transactions = intents.map { buildTransaction(it.intent) }
        emit(JitoBundleUpdate.TransactionsBuilt(transactions.size))
        
        // Sign all
        val signedTxs = transactions.map { signer.sign(it) }
        emit(JitoBundleUpdate.AllSigned(signedTxs.size))
        
        // Get JITO tip
        val tipFloor = broadcaster.getJitoTipFloor()
        val tip = maxOf(tipFloor, config.jitoConfig.minimumTip)
        emit(JitoBundleUpdate.TipCalculated(tip))
        
        // Submit bundle
        val bundleId = broadcaster.submitJitoBundle(signedTxs, tip)
        emit(JitoBundleUpdate.BundleSubmitted(bundleId))
        
        // Wait for landing
        val result = awaitBundleLanding(bundleId)
        emit(JitoBundleUpdate.BundleLanded(result))
    }
    
    /**
     * Execute PumpFun operation
     */
    fun executePumpfun(intent: TransactionIntent): Flow<PumpfunUpdate> = flow {
        when (intent) {
            is TransactionIntent.PumpfunBuy -> {
                emit(PumpfunUpdate.Started(PumpfunOperation.BUY, intent.tokenMint))
                
                // Get current bonding curve state
                val curveState = broadcaster.getPumpfunCurveState(intent.tokenMint)
                emit(PumpfunUpdate.CurveStateLoaded(curveState))
                
                // Calculate expected tokens
                val expectedTokens = calculatePumpfunBuyAmount(intent.solAmount, curveState)
                emit(PumpfunUpdate.QuoteReceived(expectedTokens, intent.solAmount))
                
                // Build and execute
                val tx = buildPumpfunBuy(intent, curveState)
                val signedTx = signer.sign(tx)
                val signature = broadcaster.broadcastWithJito(signedTx, config.jitoConfig.defaultTip)
                
                emit(PumpfunUpdate.Executed(signature, expectedTokens))
            }
            is TransactionIntent.PumpfunSell -> {
                emit(PumpfunUpdate.Started(PumpfunOperation.SELL, intent.tokenMint))
                
                val curveState = broadcaster.getPumpfunCurveState(intent.tokenMint)
                emit(PumpfunUpdate.CurveStateLoaded(curveState))
                
                val expectedSol = calculatePumpfunSellAmount(intent.tokenAmount, curveState)
                emit(PumpfunUpdate.QuoteReceived(intent.tokenAmount, expectedSol))
                
                val tx = buildPumpfunSell(intent, curveState)
                val signedTx = signer.sign(tx)
                val signature = broadcaster.broadcastWithJito(signedTx, config.jitoConfig.defaultTip)
                
                emit(PumpfunUpdate.Executed(signature, expectedSol))
            }
            is TransactionIntent.PumpfunCreate -> {
                emit(PumpfunUpdate.Started(PumpfunOperation.CREATE, "new"))
                
                val tx = buildPumpfunCreate(intent)
                val signedTx = signer.sign(tx)
                val signature = broadcaster.broadcast(signedTx)
                
                emit(PumpfunUpdate.TokenCreated(signature, intent.name, intent.symbol))
            }
            else -> throw ExecutionException.UnsupportedIntent(intent)
        }
    }
    
    /**
     * Start Yellowstone subscription based on intent
     */
    suspend fun startSubscription(intent: TransactionIntent): Flow<YellowstoneUpdate> = flow {
        when (intent) {
            is TransactionIntent.SubscribeAccount -> {
                emit(YellowstoneUpdate.Subscribing(SubscriptionType.ACCOUNT, intent.resolvedAddress))
                
                broadcaster.subscribeAccount(intent.resolvedAddress).collect { update ->
                    emit(YellowstoneUpdate.AccountUpdate(
                        address = intent.resolvedAddress,
                        slot = update.slot,
                        data = update.data
                    ))
                }
            }
            is TransactionIntent.SubscribeSlot -> {
                emit(YellowstoneUpdate.Subscribing(SubscriptionType.SLOT, "all"))
                
                broadcaster.subscribeSlots().collect { slot ->
                    emit(YellowstoneUpdate.SlotUpdate(slot))
                }
            }
            else -> throw ExecutionException.UnsupportedIntent(intent)
        }
    }
    
    private suspend fun buildTransaction(intent: TransactionIntent): UnsignedTransaction {
        return when (intent) {
            is TransactionIntent.TransferSol -> buildSolTransfer(intent)
            is TransactionIntent.TransferToken -> buildTokenTransfer(intent)
            is TransactionIntent.Swap -> buildSwap(intent)
            is TransactionIntent.Stake -> buildStake(intent)
            is TransactionIntent.PumpfunBuy -> throw ExecutionException.UnsupportedIntent(intent) // Use executePumpfun
            is TransactionIntent.PumpfunSell -> throw ExecutionException.UnsupportedIntent(intent)
            else -> throw ExecutionException.UnsupportedIntent(intent)
        }
    }
    
    private suspend fun buildSolTransfer(intent: TransactionIntent.TransferSol): UnsignedTransaction {
        return UnsignedTransaction(
            type = TransactionType.TRANSFER,
            instructions = listOf(
                Instruction.Transfer(
                    from = config.walletAddress,
                    to = intent.resolvedAddress,
                    lamports = intent.amount.multiply(BigDecimal(1_000_000_000)).toLong()
                )
            ),
            recentBlockhash = null,
            feePayer = config.walletAddress
        )
    }
    
    private suspend fun buildTokenTransfer(intent: TransactionIntent.TransferToken): UnsignedTransaction {
        return UnsignedTransaction(
            type = TransactionType.TOKEN_TRANSFER,
            instructions = listOf(
                Instruction.TokenTransfer(
                    mint = intent.mint,
                    from = config.walletAddress,
                    to = intent.resolvedAddress,
                    amount = intent.amount
                )
            ),
            recentBlockhash = null,
            feePayer = config.walletAddress
        )
    }
    
    private suspend fun buildSwap(intent: TransactionIntent.Swap): UnsignedTransaction {
        // Use Metis for swap routing
        return UnsignedTransaction(
            type = TransactionType.SWAP,
            instructions = emptyList(),
            recentBlockhash = null,
            feePayer = config.walletAddress,
            metadata = mapOf(
                "inputMint" to intent.inputMint,
                "outputMint" to intent.outputMint,
                "amount" to intent.amount.toString(),
                "slippage" to intent.slippageBps.toString(),
                "useJito" to intent.useJito.toString()
            )
        )
    }
    
    private suspend fun buildStake(intent: TransactionIntent.Stake): UnsignedTransaction {
        return UnsignedTransaction(
            type = TransactionType.STAKE,
            instructions = emptyList(),
            recentBlockhash = null,
            feePayer = config.walletAddress,
            metadata = mapOf(
                "amount" to intent.amount.toString(),
                "protocol" to (intent.protocol ?: "native")
            )
        )
    }
    
    private suspend fun buildPumpfunBuy(
        intent: TransactionIntent.PumpfunBuy,
        curveState: BondingCurveState
    ): UnsignedTransaction {
        return UnsignedTransaction(
            type = TransactionType.PUMPFUN_BUY,
            instructions = emptyList(),
            recentBlockhash = null,
            feePayer = config.walletAddress,
            metadata = mapOf(
                "tokenMint" to intent.tokenMint,
                "solAmount" to intent.solAmount.toString(),
                "slippage" to intent.slippageBps.toString(),
                "curve" to curveState.address
            )
        )
    }
    
    private suspend fun buildPumpfunSell(
        intent: TransactionIntent.PumpfunSell,
        curveState: BondingCurveState
    ): UnsignedTransaction {
        return UnsignedTransaction(
            type = TransactionType.PUMPFUN_SELL,
            instructions = emptyList(),
            recentBlockhash = null,
            feePayer = config.walletAddress,
            metadata = mapOf(
                "tokenMint" to intent.tokenMint,
                "tokenAmount" to intent.tokenAmount.toString(),
                "slippage" to intent.slippageBps.toString(),
                "curve" to curveState.address
            )
        )
    }
    
    private suspend fun buildPumpfunCreate(intent: TransactionIntent.PumpfunCreate): UnsignedTransaction {
        return UnsignedTransaction(
            type = TransactionType.PUMPFUN_CREATE,
            instructions = emptyList(),
            recentBlockhash = null,
            feePayer = config.walletAddress,
            metadata = mapOf(
                "name" to intent.name,
                "symbol" to intent.symbol,
                "description" to intent.description
            )
        )
    }
    
    private suspend fun broadcastWithStrategy(
        signedTx: SignedTransaction,
        intent: TransactionIntent
    ): String {
        return when (determineBroadcastMethod(intent)) {
            BroadcastMethod.JITO -> broadcaster.broadcastWithJito(signedTx, config.jitoConfig.defaultTip)
            BroadcastMethod.FASTLANE -> broadcaster.broadcastWithFastlane(signedTx)
            BroadcastMethod.STANDARD -> broadcaster.broadcast(signedTx)
        }
    }
    
    private fun determineBroadcastMethod(intent: TransactionIntent): BroadcastMethod {
        return when (intent) {
            is TransactionIntent.Swap -> if (intent.useJito) BroadcastMethod.JITO else BroadcastMethod.STANDARD
            is TransactionIntent.PumpfunBuy -> BroadcastMethod.JITO
            is TransactionIntent.PumpfunSell -> BroadcastMethod.JITO
            else -> BroadcastMethod.STANDARD
        }
    }
    
    private suspend fun generatePreview(intent: TransactionIntent): TransactionPreview {
        return TransactionPreview(
            intent = intent,
            summary = intent.summary(),
            estimatedFee = estimateFee(intent),
            estimatedTime = estimateTime(intent),
            warnings = detectWarnings(intent),
            riskLevel = assessRisk(intent),
            mevProtection = determineMevProtection(intent)
        )
    }
    
    private fun determineMevProtection(intent: TransactionIntent): MevProtection {
        return when (intent) {
            is TransactionIntent.Swap -> if (intent.useJito) MevProtection.JITO else MevProtection.NONE
            is TransactionIntent.PumpfunBuy -> MevProtection.JITO
            is TransactionIntent.PumpfunSell -> MevProtection.JITO
            else -> MevProtection.NONE
        }
    }
    
    private suspend fun estimateFee(intent: TransactionIntent): BigDecimal {
        val baseFee = BigDecimal("0.000005")
        val priorityMultiplier = when (intent) {
            is TransactionIntent.Swap -> BigDecimal("1.5")
            is TransactionIntent.PumpfunBuy -> BigDecimal("2.0")
            is TransactionIntent.PumpfunSell -> BigDecimal("2.0")
            else -> BigDecimal.ONE
        }
        return baseFee.multiply(priorityMultiplier)
    }
    
    private suspend fun estimateTime(intent: TransactionIntent): Long {
        return when (intent) {
            is TransactionIntent.Swap -> 10L
            is TransactionIntent.PumpfunBuy -> 5L
            is TransactionIntent.PumpfunSell -> 5L
            else -> 5L
        }
    }
    
    private suspend fun detectWarnings(intent: TransactionIntent): List<Warning> {
        val warnings = mutableListOf<Warning>()
        
        when (intent) {
            is TransactionIntent.PumpfunBuy -> {
                warnings.add(Warning.HighRisk("PumpFun tokens are highly volatile"))
                if (intent.solAmount > BigDecimal("1")) {
                    warnings.add(Warning.LargeAmount(intent.solAmount))
                }
            }
            is TransactionIntent.Swap -> {
                if (intent.slippageBps > 100) {
                    warnings.add(Warning.HighSlippage(intent.slippageBps))
                }
            }
            else -> {}
        }
        
        return warnings
    }
    
    private fun assessRisk(intent: TransactionIntent): RiskLevel {
        return when (intent) {
            is TransactionIntent.PumpfunBuy -> RiskLevel.HIGH
            is TransactionIntent.PumpfunSell -> RiskLevel.HIGH
            is TransactionIntent.Swap -> RiskLevel.MEDIUM
            else -> RiskLevel.LOW
        }
    }
    
    private fun calculatePumpfunBuyAmount(solAmount: BigDecimal, curve: BondingCurveState): BigDecimal {
        // Simplified bonding curve calculation
        return solAmount.multiply(curve.tokensPerSol)
    }
    
    private fun calculatePumpfunSellAmount(tokenAmount: BigDecimal, curve: BondingCurveState): BigDecimal {
        return tokenAmount.divide(curve.tokensPerSol, java.math.RoundingMode.DOWN)
    }
    
    private suspend fun awaitConfirmation(signature: String): Confirmation {
        var attempts = 0
        val maxAttempts = 30
        var delayMs = 500L
        
        while (attempts < maxAttempts) {
            val status = broadcaster.getStatus(signature)
            if (status.confirmed) {
                return Confirmation(
                    signature = signature,
                    slot = status.slot,
                    confirmations = status.confirmations,
                    finalized = status.finalized
                )
            }
            kotlinx.coroutines.delay(delayMs)
            delayMs = minOf(delayMs * 2, 5000L)
            attempts++
        }
        
        throw ExecutionException.ConfirmationTimeout(signature)
    }
    
    private suspend fun awaitBundleLanding(bundleId: String): BundleResult {
        var attempts = 0
        val maxAttempts = 20
        
        while (attempts < maxAttempts) {
            val status = broadcaster.getBundleStatus(bundleId)
            when (status) {
                is BundleStatus.Landed -> return BundleResult.Success(status.slot, status.signatures)
                is BundleStatus.Failed -> return BundleResult.Failed(status.reason)
                is BundleStatus.Pending -> {
                    kotlinx.coroutines.delay(500)
                    attempts++
                }
            }
        }
        
        return BundleResult.Timeout
    }
}

// === Execution State & Updates ===

sealed class ExecutionState {
    data object Idle : ExecutionState()
    data class Preparing(val intent: TransactionIntent) : ExecutionState()
    data class AwaitingConfirmation(val preview: TransactionPreview) : ExecutionState()
    data class AwaitingSignature(val transaction: UnsignedTransaction) : ExecutionState()
    data class Broadcasting(val signedTx: SignedTransaction) : ExecutionState()
    data class Completed(val record: ExecutionRecord) : ExecutionState()
    data class Failed(val error: ExecutionException) : ExecutionState()
}

sealed class ExecutionUpdate {
    data class Started(val intent: TransactionIntent) : ExecutionUpdate()
    data class Preview(val preview: TransactionPreview) : ExecutionUpdate()
    data class TransactionBuilt(val transaction: UnsignedTransaction) : ExecutionUpdate()
    data class Signed(val signedTx: SignedTransaction) : ExecutionUpdate()
    data class Broadcasted(val signature: String) : ExecutionUpdate()
    data class Confirmed(val confirmation: Confirmation) : ExecutionUpdate()
    data class Completed(val record: ExecutionRecord) : ExecutionUpdate()
    data class Failed(val error: ExecutionException) : ExecutionUpdate()
}

// === JITO Bundle Updates ===

sealed class JitoBundleUpdate {
    data class Started(val count: Int) : JitoBundleUpdate()
    data class TransactionsBuilt(val count: Int) : JitoBundleUpdate()
    data class AllSigned(val count: Int) : JitoBundleUpdate()
    data class TipCalculated(val tipLamports: Long) : JitoBundleUpdate()
    data class BundleSubmitted(val bundleId: String) : JitoBundleUpdate()
    data class BundleLanded(val result: BundleResult) : JitoBundleUpdate()
}

sealed class BundleResult {
    data class Success(val slot: Long, val signatures: List<String>) : BundleResult()
    data class Failed(val reason: String) : BundleResult()
    data object Timeout : BundleResult()
}

sealed class BundleStatus {
    data class Pending(val position: Int) : BundleStatus()
    data class Landed(val slot: Long, val signatures: List<String>) : BundleStatus()
    data class Failed(val reason: String) : BundleStatus()
}

// === PumpFun Updates ===

sealed class PumpfunUpdate {
    data class Started(val operation: PumpfunOperation, val tokenMint: String) : PumpfunUpdate()
    data class CurveStateLoaded(val state: BondingCurveState) : PumpfunUpdate()
    data class QuoteReceived(val tokenAmount: BigDecimal, val solAmount: BigDecimal) : PumpfunUpdate()
    data class Executed(val signature: String, val amount: BigDecimal) : PumpfunUpdate()
    data class TokenCreated(val signature: String, val name: String, val symbol: String) : PumpfunUpdate()
}

enum class PumpfunOperation { BUY, SELL, CREATE }

data class BondingCurveState(
    val address: String,
    val virtualSolReserves: BigDecimal,
    val virtualTokenReserves: BigDecimal,
    val tokensPerSol: BigDecimal,
    val complete: Boolean
)

// === Yellowstone Updates ===

sealed class YellowstoneUpdate {
    data class Subscribing(val type: SubscriptionType, val target: String) : YellowstoneUpdate()
    data class AccountUpdate(val address: String, val slot: Long, val data: ByteArray) : YellowstoneUpdate() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as AccountUpdate
            return address == other.address && slot == other.slot
        }
        override fun hashCode(): Int = address.hashCode() + slot.hashCode()
    }
    data class SlotUpdate(val slot: Long) : YellowstoneUpdate()
    data class TransactionUpdate(val signature: String, val slot: Long) : YellowstoneUpdate()
}

enum class SubscriptionType { ACCOUNT, SLOT, TRANSACTION, BLOCK }

data class AccountUpdateData(
    val slot: Long,
    val data: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as AccountUpdateData
        return slot == other.slot && data.contentEquals(other.data)
    }
    override fun hashCode(): Int = slot.hashCode() + data.contentHashCode()
}

// === Configuration ===

data class IrisExecutorConfig(
    val walletAddress: String = "",
    val minimumConfidence: Double = 0.80,
    val resetDelayMs: Long = 2000L,
    val jitoConfig: JitoConfig = JitoConfig(),
    val fastlaneConfig: FastlaneConfig = FastlaneConfig()
)

data class JitoConfig(
    val enabled: Boolean = true,
    val minimumTip: Long = 10000,
    val defaultTip: Long = 25000,
    val maxTip: Long = 1000000,
    val autoTip: Boolean = true
)

data class FastlaneConfig(
    val enabled: Boolean = false,
    val priorityLevel: PriorityLevel = PriorityLevel.MEDIUM
)

enum class PriorityLevel { LOW, MEDIUM, HIGH, ULTRA }

// === Transaction Types ===

data class TransactionPreview(
    val intent: TransactionIntent,
    val summary: String,
    val estimatedFee: BigDecimal,
    val estimatedTime: Long,
    val warnings: List<Warning>,
    val riskLevel: RiskLevel,
    val mevProtection: MevProtection
) {
    fun toHumanReadable(): String = buildString {
        appendLine("📝 Transaction Preview")
        appendLine("━━━━━━━━━━━━━━━━━━━━━━")
        appendLine(summary)
        appendLine()
        appendLine("⚡ Estimated Fee: $estimatedFee SOL")
        appendLine("⏱️ Estimated Time: ${estimatedTime}s")
        appendLine("🛡️ MEV Protection: $mevProtection")
        if (warnings.isNotEmpty()) {
            appendLine()
            appendLine("⚠️ Warnings:")
            warnings.forEach { appendLine("  • ${it.message}") }
        }
        appendLine()
        appendLine("🔒 Risk Level: $riskLevel")
    }
}

enum class MevProtection { NONE, JITO, FASTLANE }

data class ExecutionRecord(
    val intent: TransactionIntent,
    val signature: String,
    val confirmation: Confirmation,
    val timestamp: Long,
    val broadcastMethod: BroadcastMethod
)

enum class BroadcastMethod { STANDARD, JITO, FASTLANE }

data class Confirmation(
    val signature: String,
    val slot: Long,
    val confirmations: Int,
    val finalized: Boolean
)

enum class TransactionType {
    TRANSFER, TOKEN_TRANSFER, SWAP, STAKE, UNSTAKE,
    PUMPFUN_BUY, PUMPFUN_SELL, PUMPFUN_CREATE,
    NFT, CUSTOM
}

enum class RiskLevel { LOW, MEDIUM, HIGH, CRITICAL }

sealed class Warning(open val message: String) {
    data class LargeAmount(val amount: BigDecimal) : Warning("Large amount: $amount SOL")
    data class HighSlippage(val bps: Int) : Warning("High slippage: ${bps / 100.0}%")
    data class HighRisk(override val message: String) : Warning(message)
    data class UnverifiedToken(val mint: String) : Warning("Unverified token: $mint")
}

sealed class ExecutionException(override val message: String) : Exception(message) {
    data class UnsupportedIntent(val intent: TransactionIntent) : ExecutionException("Unsupported intent")
    data class SigningFailed(val reason: String) : ExecutionException("Signing failed: $reason")
    data class BroadcastFailed(val reason: String) : ExecutionException("Broadcast failed: $reason")
    data class ConfirmationTimeout(val signature: String) : ExecutionException("Confirmation timeout")
    data class JitoBundleFailed(val reason: String) : ExecutionException("JITO bundle failed: $reason")
}

// === Transaction Data ===

data class UnsignedTransaction(
    val type: TransactionType,
    val instructions: List<Instruction>,
    val recentBlockhash: String?,
    val feePayer: String,
    val metadata: Map<String, String> = emptyMap()
)

data class SignedTransaction(
    val unsigned: UnsignedTransaction,
    val signatures: List<String>,
    val serialized: ByteArray
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as SignedTransaction
        return signatures == other.signatures
    }
    override fun hashCode(): Int = signatures.hashCode()
}

sealed class Instruction {
    data class Transfer(val from: String, val to: String, val lamports: Long) : Instruction()
    data class TokenTransfer(val mint: String, val from: String, val to: String, val amount: BigDecimal) : Instruction()
}

// === Interfaces ===

interface TransactionSigner {
    suspend fun sign(transaction: UnsignedTransaction): SignedTransaction
    suspend fun signBatch(transactions: List<UnsignedTransaction>): List<SignedTransaction>
}

interface IrisTransactionBroadcaster {
    suspend fun broadcast(transaction: SignedTransaction): String
    suspend fun broadcastWithJito(transaction: SignedTransaction, tipLamports: Long): String
    suspend fun broadcastWithFastlane(transaction: SignedTransaction): String
    suspend fun getStatus(signature: String): TransactionStatus
    
    // JITO specific
    suspend fun getJitoTipFloor(): Long
    suspend fun submitJitoBundle(transactions: List<SignedTransaction>, tipLamports: Long): String
    suspend fun getBundleStatus(bundleId: String): BundleStatus
    
    // PumpFun specific
    suspend fun getPumpfunCurveState(tokenMint: String): BondingCurveState
    
    // Yellowstone specific
    fun subscribeAccount(address: String): Flow<AccountUpdateData>
    fun subscribeSlots(): Flow<Long>
}

data class TransactionStatus(
    val signature: String,
    val confirmed: Boolean,
    val slot: Long,
    val confirmations: Int,
    val finalized: Boolean,
    val error: String? = null
)
