package xyz.selenus.iris.nlp

import kotlinx.serialization.Serializable
import java.math.BigDecimal

/**
 * Result of parsing natural language input
 */
sealed class ParseResult {
    /**
     * Successfully parsed into a transaction intent
     */
    data class Success(
        val intent: TransactionIntent,
        val confidence: Double,
        val rawInput: String
    ) : ParseResult() {
        fun summary(): String = intent.summary()
    }
    
    /**
     * Multiple interpretations possible - user should clarify
     */
    data class Ambiguous(
        val primary: TransactionIntent,
        val alternatives: List<TransactionIntent>,
        val confidence: Double
    ) : ParseResult()
    
    /**
     * Partial understanding - need more information
     */
    data class NeedsInfo(
        val intentType: IntentType,
        val missing: List<String>,
        val partial: Map<String, String>,
        val suggestion: String
    ) : ParseResult()
    
    /**
     * Could not understand the input
     */
    data class Unknown(
        val input: String,
        val suggestions: List<CommandSuggestion>
    ) : ParseResult()
}

/**
 * Types of transaction intents supported by Iris SDK (QuickNode)
 */
enum class IntentType {
    // Transfers
    TRANSFER_SOL,
    TRANSFER_TOKEN,
    
    // Swaps (Metis/Jupiter)
    SWAP,
    SWAP_EXACT_OUT,
    SWAP_LIMIT_ORDER,
    
    // Staking
    STAKE,
    UNSTAKE,
    CLAIM_REWARDS,
    
    // NFT Operations
    NFT_TRANSFER,
    NFT_LIST,
    NFT_BUY,
    NFT_BURN,
    
    // DAS Queries
    GET_ASSETS,
    GET_ASSET_PROOF,
    SEARCH_ASSETS,
    
    // JITO (MEV Protection)
    JITO_TIP,
    JITO_BUNDLE,
    
    // Priority Fees
    GET_PRIORITY_FEE,
    
    // Yellowstone (gRPC)
    SUBSCRIBE_ACCOUNT,
    SUBSCRIBE_SLOT,
    
    // PumpFun
    PUMPFUN_BUY,
    PUMPFUN_SELL,
    PUMPFUN_CREATE,
    
    // Fastlane
    FASTLANE_SUBMIT,
    
    // Balance & Info
    GET_BALANCE,
    GET_TOKEN_BALANCE,
    GET_ACCOUNT_INFO,
    
    // Domain Resolution
    RESOLVE_DOMAIN,
    REVERSE_LOOKUP,
    GET_DOMAINS,
    
    // Privacy
    ANALYZE_PRIVACY,
    GENERATE_STEALTH_ADDRESS,
    
    // Custom
    CUSTOM
}

/**
 * Base class for all transaction intents
 */
sealed class TransactionIntent {
    abstract val type: IntentType
    abstract fun summary(): String
    abstract fun details(): Map<String, String>
    
    // === TRANSFER INTENTS ===
    
    data class TransferSol(
        val amount: BigDecimal,
        val recipient: String,
        val recipientResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.TRANSFER_SOL
        override fun summary() = "Transfer $amount SOL to ${recipientResolved ?: recipient}"
        override fun details() = mapOf(
            "Amount" to "$amount SOL",
            "Recipient" to recipient,
            "Resolved" to (recipientResolved ?: "pending")
        )
    }
    
    data class TransferToken(
        val amount: BigDecimal,
        val token: String,
        val tokenMint: String? = null,
        val recipient: String,
        val recipientResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.TRANSFER_TOKEN
        override fun summary() = "Transfer $amount $token to ${recipientResolved ?: recipient}"
        override fun details() = mapOf(
            "Amount" to "$amount $token",
            "Token Mint" to (tokenMint ?: "pending"),
            "Recipient" to recipient,
            "Resolved" to (recipientResolved ?: "pending")
        )
    }
    
    // === SWAP INTENTS (METIS/JUPITER) ===
    
    data class Swap(
        val inputAmount: BigDecimal,
        val inputToken: String,
        val inputMint: String? = null,
        val outputToken: String,
        val outputMint: String? = null,
        val slippageBps: Int = 50,
        val useJito: Boolean = false
    ) : TransactionIntent() {
        override val type = IntentType.SWAP
        override fun summary() = "Swap $inputAmount $inputToken for $outputToken" + 
            if (useJito) " (JITO protected)" else ""
        override fun details() = mapOf(
            "Input" to "$inputAmount $inputToken",
            "Output" to outputToken,
            "Slippage" to "${slippageBps / 100.0}%",
            "MEV Protected" to useJito.toString()
        )
    }
    
    data class SwapExactOut(
        val outputAmount: BigDecimal,
        val outputToken: String,
        val outputMint: String? = null,
        val inputToken: String,
        val inputMint: String? = null,
        val slippageBps: Int = 50
    ) : TransactionIntent() {
        override val type = IntentType.SWAP_EXACT_OUT
        override fun summary() = "Buy $outputAmount $outputToken with $inputToken"
        override fun details() = mapOf(
            "Output" to "$outputAmount $outputToken",
            "Input Token" to inputToken,
            "Slippage" to "${slippageBps / 100.0}%"
        )
    }
    
    data class SwapLimitOrder(
        val inputAmount: BigDecimal,
        val inputToken: String,
        val outputToken: String,
        val targetPrice: BigDecimal,
        val expiry: Long? = null
    ) : TransactionIntent() {
        override val type = IntentType.SWAP_LIMIT_ORDER
        override fun summary() = "Limit order: Swap $inputAmount $inputToken for $outputToken at $targetPrice"
        override fun details() = mapOf(
            "Input" to "$inputAmount $inputToken",
            "Output" to outputToken,
            "Target Price" to targetPrice.toString(),
            "Expiry" to (expiry?.toString() ?: "no expiry")
        )
    }
    
    // === STAKING INTENTS ===
    
    data class Stake(
        val amount: BigDecimal,
        val validator: String? = null,
        val protocol: String? = null // "marinade", "jito", "blaze", etc.
    ) : TransactionIntent() {
        override val type = IntentType.STAKE
        override fun summary() = "Stake $amount SOL" + 
            (protocol?.let { " with $it" } ?: "") +
            (validator?.let { " to $it" } ?: "")
        override fun details() = mapOf(
            "Amount" to "$amount SOL",
            "Protocol" to (protocol ?: "native"),
            "Validator" to (validator ?: "auto-select")
        )
    }
    
    data class Unstake(
        val amount: BigDecimal,
        val protocol: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.UNSTAKE
        override fun summary() = "Unstake $amount SOL" + (protocol?.let { " from $it" } ?: "")
        override fun details() = mapOf("Amount" to "$amount SOL", "Protocol" to (protocol ?: "native"))
    }
    
    data object ClaimRewards : TransactionIntent() {
        override val type = IntentType.CLAIM_REWARDS
        override fun summary() = "Claim staking rewards"
        override fun details() = emptyMap<String, String>()
    }
    
    // === JITO INTENTS ===
    
    data class JitoBundleIntent(
        val transactions: List<String>,
        val tipLamports: Long? = null
    ) : TransactionIntent() {
        override val type = IntentType.JITO_BUNDLE
        override fun summary() = "Submit ${transactions.size} transactions as JITO bundle"
        override fun details() = mapOf(
            "Transaction Count" to transactions.size.toString(),
            "Tip" to (tipLamports?.let { "${it / 1_000_000_000.0} SOL" } ?: "minimum")
        )
    }
    
    data class JitoTipIntent(
        val tipLamports: Long
    ) : TransactionIntent() {
        override val type = IntentType.JITO_TIP
        override fun summary() = "Get JITO tip floor (current: ${tipLamports / 1_000_000_000.0} SOL)"
        override fun details() = mapOf("Tip" to "${tipLamports / 1_000_000_000.0} SOL")
    }
    
    // === PUMPFUN INTENTS ===
    
    data class PumpfunBuy(
        val tokenMint: String,
        val solAmount: BigDecimal,
        val slippageBps: Int = 100
    ) : TransactionIntent() {
        override val type = IntentType.PUMPFUN_BUY
        override fun summary() = "Buy PumpFun token with $solAmount SOL"
        override fun details() = mapOf(
            "Token" to tokenMint,
            "Amount" to "$solAmount SOL",
            "Slippage" to "${slippageBps / 100.0}%"
        )
    }
    
    data class PumpfunSell(
        val tokenMint: String,
        val tokenAmount: BigDecimal,
        val slippageBps: Int = 100
    ) : TransactionIntent() {
        override val type = IntentType.PUMPFUN_SELL
        override fun summary() = "Sell $tokenAmount PumpFun tokens"
        override fun details() = mapOf(
            "Token" to tokenMint,
            "Amount" to tokenAmount.toString(),
            "Slippage" to "${slippageBps / 100.0}%"
        )
    }
    
    data class PumpfunCreate(
        val name: String,
        val symbol: String,
        val description: String,
        val initialBuyAmount: BigDecimal? = null
    ) : TransactionIntent() {
        override val type = IntentType.PUMPFUN_CREATE
        override fun summary() = "Create PumpFun token: $symbol"
        override fun details() = mapOf(
            "Name" to name,
            "Symbol" to symbol,
            "Description" to description,
            "Initial Buy" to (initialBuyAmount?.let { "$it SOL" } ?: "none")
        )
    }
    
    // === NFT INTENTS ===
    
    data class NftTransfer(
        val nftAddress: String,
        val recipient: String,
        val recipientResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.NFT_TRANSFER
        override fun summary() = "Transfer NFT to ${recipientResolved ?: recipient}"
        override fun details() = mapOf(
            "NFT" to nftAddress,
            "Recipient" to recipient
        )
    }
    
    data class NftList(
        val nftAddress: String,
        val price: BigDecimal,
        val marketplace: String = "MagicEden"
    ) : TransactionIntent() {
        override val type = IntentType.NFT_LIST
        override fun summary() = "List NFT for $price SOL on $marketplace"
        override fun details() = mapOf(
            "NFT" to nftAddress,
            "Price" to "$price SOL",
            "Marketplace" to marketplace
        )
    }
    
    // === QUERY INTENTS ===
    
    data class GetAssets(
        val owner: String,
        val ownerResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.GET_ASSETS
        override fun summary() = "Get assets for ${ownerResolved ?: owner}"
        override fun details() = mapOf("Owner" to owner)
    }
    
    data class GetBalance(
        val address: String,
        val addressResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.GET_BALANCE
        override fun summary() = "Get SOL balance for ${addressResolved ?: address}"
        override fun details() = mapOf("Address" to address)
    }
    
    data class GetTokenBalance(
        val address: String,
        val token: String,
        val addressResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.GET_TOKEN_BALANCE
        override fun summary() = "Get $token balance for ${addressResolved ?: address}"
        override fun details() = mapOf("Address" to address, "Token" to token)
    }
    
    // === DOMAIN INTENTS ===
    
    data class ResolveDomain(
        val domain: String
    ) : TransactionIntent() {
        override val type = IntentType.RESOLVE_DOMAIN
        override fun summary() = "Resolve domain $domain"
        override fun details() = mapOf("Domain" to domain)
    }
    
    data class ReverseLookup(
        val address: String
    ) : TransactionIntent() {
        override val type = IntentType.REVERSE_LOOKUP
        override fun summary() = "Lookup domain for $address"
        override fun details() = mapOf("Address" to address)
    }
    
    data class GetDomains(
        val owner: String,
        val ownerResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.GET_DOMAINS
        override fun summary() = "Get domains owned by ${ownerResolved ?: owner}"
        override fun details() = mapOf("Owner" to owner)
    }
    
    // === PRIVACY INTENTS ===
    
    data class AnalyzePrivacy(
        val address: String,
        val addressResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.ANALYZE_PRIVACY
        override fun summary() = "Analyze privacy for ${addressResolved ?: address}"
        override fun details() = mapOf("Address" to address)
    }
    
    data class GenerateStealthAddress(
        val recipientMetaAddress: String
    ) : TransactionIntent() {
        override val type = IntentType.GENERATE_STEALTH_ADDRESS
        override fun summary() = "Generate stealth address for $recipientMetaAddress"
        override fun details() = mapOf("Meta Address" to recipientMetaAddress)
    }
    
    // === YELLOWSTONE INTENTS ===
    
    data class SubscribeAccount(
        val address: String,
        val addressResolved: String? = null
    ) : TransactionIntent() {
        override val type = IntentType.SUBSCRIBE_ACCOUNT
        override fun summary() = "Subscribe to account updates for ${addressResolved ?: address}"
        override fun details() = mapOf("Address" to address)
    }
    
    data object SubscribeSlot : TransactionIntent() {
        override val type = IntentType.SUBSCRIBE_SLOT
        override fun summary() = "Subscribe to slot updates"
        override fun details() = emptyMap<String, String>()
    }
    
    // === FASTLANE INTENTS ===
    
    data class FastlaneSubmit(
        val transaction: String,
        val priority: String = "medium"
    ) : TransactionIntent() {
        override val type = IntentType.FASTLANE_SUBMIT
        override fun summary() = "Submit transaction via Fastlane ($priority priority)"
        override fun details() = mapOf("Priority" to priority)
    }
}

/**
 * Command suggestion for unknown inputs
 */
@Serializable
data class CommandSuggestion(
    val template: String,
    val description: String,
    val examples: List<String>
)

/**
 * Extracted entity from natural language
 */
@Serializable
data class ExtractedEntity(
    val type: EntityType,
    val value: String,
    val raw: String,
    val startIndex: Int,
    val endIndex: Int
)

/**
 * Types of entities that can be extracted
 */
enum class EntityType {
    AMOUNT,
    TOKEN,
    ADDRESS,
    DOMAIN,
    NUMBER,
    STRING,
    VALIDATOR,
    MARKETPLACE,
    URL,
    PROTOCOL,
    PRIORITY
}
