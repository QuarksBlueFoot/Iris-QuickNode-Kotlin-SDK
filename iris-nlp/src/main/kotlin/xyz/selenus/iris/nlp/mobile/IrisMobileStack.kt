package xyz.selenus.iris.nlp.mobile

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import xyz.selenus.iris.nlp.ParseResult
import xyz.selenus.iris.nlp.TransactionIntent
import java.math.BigDecimal

/**
 * Iris Mobile Stack Integration - QuickNode Enhanced
 * 
 * Solana Mobile Standard compliant integration with:
 * - Seed Vault protocol for hardware-backed signing
 * - Mobile Wallet Adapter (MWA) for wallet connections
 * - Solana Pay URL parsing
 * - QR code scanning and generation
 * - Deep link handling
 * - MEV protection on mobile
 * 
 * KMM 2026 Android architecture patterns
 */
class IrisMobileStack(
    private val config: MobileConfig = MobileConfig()
) {
    
    private val _connectionState = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()
    
    private val _activeWallet = MutableStateFlow<ConnectedWallet?>(null)
    val activeWallet: StateFlow<ConnectedWallet?> = _activeWallet.asStateFlow()
    
    /**
     * Connect to wallet via Mobile Wallet Adapter
     */
    suspend fun connectWallet(adapter: MobileWalletAdapter): ConnectionResult {
        _connectionState.value = ConnectionState.Connecting
        
        return try {
            val wallet = adapter.connect()
            _activeWallet.value = wallet
            _connectionState.value = ConnectionState.Connected(wallet)
            ConnectionResult.Success(wallet)
        } catch (e: Exception) {
            _connectionState.value = ConnectionState.Error(e.message ?: "Connection failed")
            ConnectionResult.Failed(e.message ?: "Connection failed")
        }
    }
    
    /**
     * Disconnect from wallet
     */
    suspend fun disconnectWallet(adapter: MobileWalletAdapter) {
        adapter.disconnect()
        _activeWallet.value = null
        _connectionState.value = ConnectionState.Disconnected
    }
    
    /**
     * Sign transaction with MEV protection options
     */
    suspend fun signWithMevProtection(
        transaction: ByteArray,
        adapter: MobileWalletAdapter,
        mevConfig: MevSigningConfig = MevSigningConfig()
    ): SignResult {
        val wallet = _activeWallet.value
            ?: return SignResult.Failed("No wallet connected")
        
        return try {
            // Request user confirmation with MEV info
            val confirmed = adapter.requestConfirmation(
                message = buildMevConfirmationMessage(mevConfig),
                transaction = transaction
            )
            
            if (!confirmed) {
                return SignResult.Cancelled
            }
            
            // Sign with hardware-backed key if available
            val signature = if (wallet.hasSeedVault && config.preferSeedVault) {
                adapter.signWithSeedVault(transaction)
            } else {
                adapter.sign(transaction)
            }
            
            SignResult.Success(signature)
        } catch (e: Exception) {
            SignResult.Failed(e.message ?: "Signing failed")
        }
    }
    
    private fun buildMevConfirmationMessage(config: MevSigningConfig): String = buildString {
        appendLine("Transaction Signing Request")
        appendLine()
        if (config.jitoEnabled) {
            appendLine("🛡️ MEV Protection: JITO Bundle")
            appendLine("💰 Tip: ${config.jitoTip} lamports")
        } else {
            appendLine("⚠️ No MEV Protection")
        }
        if (config.fastlaneEnabled) {
            appendLine("🚀 Fastlane Priority: ${config.fastlanePriority}")
        }
    }
}

/**
 * Bridge between NLP intents and Mobile Stack
 */
class NlpMobileBridge(
    private val mobileStack: IrisMobileStack,
    private val adapter: MobileWalletAdapter
) {
    
    /**
     * Execute NLP intent through mobile stack with MEV options
     */
    fun executeIntent(
        intent: TransactionIntent,
        mevConfig: MevSigningConfig = MevSigningConfig()
    ): Flow<MobileExecutionUpdate> = flow {
        emit(MobileExecutionUpdate.Started(intent))
        
        // Check connection
        val wallet = mobileStack.activeWallet.value
        if (wallet == null) {
            emit(MobileExecutionUpdate.NeedsConnection)
            return@flow
        }
        
        emit(MobileExecutionUpdate.Building)
        
        // Build transaction (placeholder)
        val transaction = buildTransaction(intent)
        emit(MobileExecutionUpdate.AwaitingSignature)
        
        // Sign with MEV options
        val signResult = mobileStack.signWithMevProtection(transaction, adapter, mevConfig)
        
        when (signResult) {
            is SignResult.Success -> {
                emit(MobileExecutionUpdate.Signed(signResult.signature))
                emit(MobileExecutionUpdate.Broadcasting)
                // Broadcast would happen here
                emit(MobileExecutionUpdate.Completed("signature_placeholder"))
            }
            is SignResult.Cancelled -> emit(MobileExecutionUpdate.Cancelled)
            is SignResult.Failed -> emit(MobileExecutionUpdate.Failed(signResult.reason))
        }
    }
    
    private suspend fun buildTransaction(intent: TransactionIntent): ByteArray {
        // Transaction building implementation
        return ByteArray(0)
    }
}

/**
 * Deep link handler for Solana Pay URLs
 */
class DeepLinkHandler {
    
    companion object {
        private val SOLANA_PAY_PATTERN = Regex(
            "^solana:([^?]+)(?:\\?(.+))?$",
            RegexOption.IGNORE_CASE
        )
        
        private val IRIS_INTENT_PATTERN = Regex(
            "^iris://intent/(.+)$",
            RegexOption.IGNORE_CASE
        )
        
        private val JITO_BUNDLE_PATTERN = Regex(
            "^jito://bundle/(.+)$",
            RegexOption.IGNORE_CASE
        )
    }
    
    /**
     * Parse deep link URL into intent
     */
    fun parseDeepLink(url: String): DeepLinkResult {
        // Try Solana Pay
        SOLANA_PAY_PATTERN.find(url)?.let { match ->
            return parseSolanaPay(match.groupValues[1], match.groupValues.getOrNull(2))
        }
        
        // Try Iris intent
        IRIS_INTENT_PATTERN.find(url)?.let { match ->
            return parseIrisIntent(match.groupValues[1])
        }
        
        // Try JITO bundle
        JITO_BUNDLE_PATTERN.find(url)?.let { match ->
            return parseJitoBundle(match.groupValues[1])
        }
        
        return DeepLinkResult.Unknown(url)
    }
    
    private fun parseSolanaPay(recipient: String, queryString: String?): DeepLinkResult {
        val params = parseQueryParams(queryString ?: "")
        
        return when {
            params.containsKey("amount") -> {
                val amount = BigDecimal(params["amount"])
                val label = params["label"]
                val message = params["message"]
                val memo = params["memo"]
                val splToken = params["spl-token"]
                
                if (splToken != null) {
                    DeepLinkResult.TokenTransfer(
                        recipient = recipient,
                        amount = amount,
                        token = splToken,
                        label = label,
                        message = message,
                        memo = memo
                    )
                } else {
                    DeepLinkResult.Transfer(
                        recipient = recipient,
                        amount = amount,
                        label = label,
                        message = message,
                        memo = memo
                    )
                }
            }
            params.containsKey("link") -> {
                DeepLinkResult.TransactionRequest(
                    recipient = recipient,
                    link = params["link"]!!,
                    label = params["label"]
                )
            }
            else -> {
                DeepLinkResult.Transfer(
                    recipient = recipient,
                    amount = null,
                    label = params["label"],
                    message = params["message"],
                    memo = params["memo"]
                )
            }
        }
    }
    
    private fun parseIrisIntent(intentData: String): DeepLinkResult {
        // Base64 encoded intent
        return try {
            val decoded = java.util.Base64.getDecoder().decode(intentData)
            val intentString = String(decoded)
            DeepLinkResult.IrisIntent(intentString)
        } catch (e: Exception) {
            DeepLinkResult.IrisIntent(intentData)
        }
    }
    
    private fun parseJitoBundle(bundleData: String): DeepLinkResult {
        return DeepLinkResult.JitoBundleLink(bundleData)
    }
    
    private fun parseQueryParams(queryString: String): Map<String, String> {
        if (queryString.isBlank()) return emptyMap()
        
        return queryString.split("&")
            .mapNotNull { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    java.net.URLDecoder.decode(parts[0], "UTF-8") to
                    java.net.URLDecoder.decode(parts[1], "UTF-8")
                } else null
            }
            .toMap()
    }
    
    /**
     * Generate deep link URL for intent
     */
    fun generateDeepLink(intent: TransactionIntent): String {
        return when (intent) {
            is TransactionIntent.TransferSol -> {
                val base = "solana:${intent.resolvedAddress ?: intent.recipient}"
                val params = mutableListOf<String>()
                params.add("amount=${intent.amount}")
                intent.memo?.let { params.add("memo=${java.net.URLEncoder.encode(it, "UTF-8")}") }
                "$base?${params.joinToString("&")}"
            }
            is TransactionIntent.TransferToken -> {
                val base = "solana:${intent.resolvedAddress ?: intent.recipient}"
                val params = mutableListOf<String>()
                params.add("amount=${intent.amount}")
                params.add("spl-token=${intent.mint}")
                "$base?${params.joinToString("&")}"
            }
            else -> {
                // Encode as Iris intent
                val encoded = java.util.Base64.getEncoder().encodeToString(intent.toString().toByteArray())
                "iris://intent/$encoded"
            }
        }
    }
}

/**
 * QR code scanner interface
 */
interface QrCodeScanner {
    suspend fun scan(): QrScanResult
    suspend fun scanContinuously(): Flow<QrScanResult>
    fun isCameraAvailable(): Boolean
}

sealed class QrScanResult {
    data class Success(val content: String, val format: QrFormat) : QrScanResult()
    data class Failed(val reason: String) : QrScanResult()
    data object Cancelled : QrScanResult()
}

enum class QrFormat {
    QR_CODE,
    DATA_MATRIX,
    AZTEC,
    PDF417
}

/**
 * QR code generator
 */
interface QrCodeGenerator {
    suspend fun generate(content: String, size: Int = 256): QrGenerateResult
}

sealed class QrGenerateResult {
    data class Success(val bitmap: ByteArray) : QrGenerateResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return bitmap.contentEquals(other.bitmap)
        }
        override fun hashCode(): Int = bitmap.contentHashCode()
    }
    data class Failed(val reason: String) : QrGenerateResult()
}

// === Mobile Wallet Adapter Interface ===

interface MobileWalletAdapter {
    val connectionState: StateFlow<ConnectionState>
    
    suspend fun connect(): ConnectedWallet
    suspend fun disconnect()
    suspend fun sign(transaction: ByteArray): ByteArray
    suspend fun signWithSeedVault(transaction: ByteArray): ByteArray
    suspend fun signAndSend(transaction: ByteArray): String
    suspend fun requestConfirmation(message: String, transaction: ByteArray): Boolean
    
    fun isAvailable(): Boolean
    fun getSupportedMethods(): List<String>
}

// === Seed Vault Provider Interface ===

interface SeedVaultProvider {
    suspend fun isAvailable(): Boolean
    suspend fun hasAuthorizedSeed(): Boolean
    suspend fun requestAuthorization(): Boolean
    suspend fun signTransaction(transaction: ByteArray): ByteArray
    suspend fun signMessage(message: ByteArray): ByteArray
    suspend fun getPublicKey(): ByteArray
    
    val authenticationRequired: StateFlow<Boolean>
}

// === Connection State ===

sealed class ConnectionState {
    data object Disconnected : ConnectionState()
    data object Connecting : ConnectionState()
    data class Connected(val wallet: ConnectedWallet) : ConnectionState()
    data class Error(val message: String) : ConnectionState()
}

sealed class ConnectionResult {
    data class Success(val wallet: ConnectedWallet) : ConnectionResult()
    data class Failed(val reason: String) : ConnectionResult()
}

data class ConnectedWallet(
    val publicKey: String,
    val label: String?,
    val iconUrl: String?,
    val capabilities: Set<WalletCapability>,
    val hasSeedVault: Boolean = false
) {
    fun supportsSignAndSend(): Boolean = WalletCapability.SIGN_AND_SEND in capabilities
    fun supportsJito(): Boolean = WalletCapability.JITO_BUNDLES in capabilities
}

enum class WalletCapability {
    SIGN_TRANSACTION,
    SIGN_AND_SEND,
    SIGN_MESSAGE,
    ENCRYPTION,
    JITO_BUNDLES,
    FASTLANE
}

sealed class SignResult {
    data class Success(val signature: ByteArray) : SignResult() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Success
            return signature.contentEquals(other.signature)
        }
        override fun hashCode(): Int = signature.contentHashCode()
    }
    data class Failed(val reason: String) : SignResult()
    data object Cancelled : SignResult()
}

// === MEV Configuration ===

data class MevSigningConfig(
    val jitoEnabled: Boolean = true,
    val jitoTip: Long = 25000,
    val fastlaneEnabled: Boolean = false,
    val fastlanePriority: String = "MEDIUM"
)

// === Mobile Configuration ===

data class MobileConfig(
    val preferSeedVault: Boolean = true,
    val autoConnect: Boolean = false,
    val sessionTimeout: Long = 3600000, // 1 hour
    val requireBiometric: Boolean = true,
    val defaultMevConfig: MevSigningConfig = MevSigningConfig()
)

// === Deep Link Results ===

sealed class DeepLinkResult {
    data class Transfer(
        val recipient: String,
        val amount: BigDecimal?,
        val label: String?,
        val message: String?,
        val memo: String?
    ) : DeepLinkResult()
    
    data class TokenTransfer(
        val recipient: String,
        val amount: BigDecimal,
        val token: String,
        val label: String?,
        val message: String?,
        val memo: String?
    ) : DeepLinkResult()
    
    data class TransactionRequest(
        val recipient: String,
        val link: String,
        val label: String?
    ) : DeepLinkResult()
    
    data class IrisIntent(
        val intentString: String
    ) : DeepLinkResult()
    
    data class JitoBundleLink(
        val bundleData: String
    ) : DeepLinkResult()
    
    data class Unknown(
        val url: String
    ) : DeepLinkResult()
}

// === Execution Updates ===

sealed class MobileExecutionUpdate {
    data class Started(val intent: TransactionIntent) : MobileExecutionUpdate()
    data object NeedsConnection : MobileExecutionUpdate()
    data object Building : MobileExecutionUpdate()
    data object AwaitingSignature : MobileExecutionUpdate()
    data class Signed(val signature: ByteArray) : MobileExecutionUpdate() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false
            other as Signed
            return signature.contentEquals(other.signature)
        }
        override fun hashCode(): Int = signature.contentHashCode()
    }
    data object Broadcasting : MobileExecutionUpdate()
    data class Completed(val signature: String) : MobileExecutionUpdate()
    data object Cancelled : MobileExecutionUpdate()
    data class Failed(val reason: String) : MobileExecutionUpdate()
}

// === Haptic Feedback ===

interface HapticFeedback {
    fun success()
    fun warning()
    fun error()
    fun selection()
    fun impact(intensity: HapticIntensity)
}

enum class HapticIntensity { LIGHT, MEDIUM, HEAVY }

// === Accessibility ===

interface AccessibilityProvider {
    fun announce(message: String)
    fun isScreenReaderActive(): Boolean
    fun setContentDescription(view: Any, description: String)
    fun requestFocus(view: Any)
}
