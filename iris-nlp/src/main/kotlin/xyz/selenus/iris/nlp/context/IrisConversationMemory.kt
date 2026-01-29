package xyz.selenus.iris.nlp.context

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import xyz.selenus.iris.nlp.NaturalLanguageBuilder
import xyz.selenus.iris.nlp.ParseResult
import xyz.selenus.iris.nlp.TransactionIntent
import java.math.BigDecimal
import java.util.concurrent.ConcurrentHashMap

/**
 * Iris Conversation Memory - QuickNode Enhanced
 * 
 * Multi-turn conversation state management with:
 * - Context-aware parsing (pronouns, references)
 * - Intent continuation and modification
 * - History recall and repeat operations
 * - User preference learning
 * - QuickNode-specific context (JITO tips, MEV settings)
 * 
 * Solana Mobile Standard compliant with KMM 2026 architecture
 */
class IrisConversationMemory(
    private val nlpBuilder: NaturalLanguageBuilder,
    private val config: MemoryConfig = MemoryConfig(),
    private val storage: MemoryStorage? = null
) {
    
    private val _context = MutableStateFlow(ConversationContext.empty())
    val context: StateFlow<ConversationContext> = _context.asStateFlow()
    
    private val _suggestions = MutableStateFlow<List<ContextualSuggestion>>(emptyList())
    val suggestions: StateFlow<List<ContextualSuggestion>> = _suggestions.asStateFlow()
    
    private val userPreferences = UserPreferences()
    private val mevPreferences = MevPreferences()
    
    private val conversationHistory = mutableListOf<ConversationTurn>()
    private val undoStack = mutableListOf<ConversationContext>()
    private val redoStack = mutableListOf<ConversationContext>()
    
    init {
        // Load persisted preferences
        storage?.let { loadPreferences(it) }
    }
    
    /**
     * Parse input with full context awareness
     */
    suspend fun parseWithContext(input: String): ContextualParseResult {
        val trimmed = input.trim()
        val currentContext = _context.value
        
        // Check for special commands first
        when {
            isUndoCommand(trimmed) -> return handleUndo()
            isRedoCommand(trimmed) -> return handleRedo()
            isRepeatCommand(trimmed) -> return handleRepeat()
            isCancelCommand(trimmed) -> return handleCancel()
            isConfirmCommand(trimmed) -> return handleConfirm()
            isModifyCommand(trimmed) -> return handleModify(trimmed)
            isHelpCommand(trimmed) -> return handleHelp()
            isMevSettingCommand(trimmed) -> return handleMevSetting(trimmed)
        }
        
        // Resolve pronouns and references
        val resolved = resolveReferences(trimmed, currentContext)
        
        // Parse with NLP
        val parseResult = nlpBuilder.parse(resolved)
        
        // Create new context
        val newContext = when (parseResult) {
            is ParseResult.Success -> {
                updatePreferences(parseResult.intent)
                val newCtx = currentContext.withIntent(parseResult.intent, trimmed)
                
                // Add conversation turn
                conversationHistory.add(ConversationTurn(
                    input = trimmed,
                    resolved = resolved,
                    result = parseResult,
                    timestamp = System.currentTimeMillis()
                ))
                
                newCtx
            }
            else -> currentContext.copy(
                lastInput = trimmed,
                lastError = (parseResult as? ParseResult.Failure)?.reason
            )
        }
        
        // Save for undo
        undoStack.add(_context.value)
        redoStack.clear()
        
        // Update context
        _context.value = newContext
        
        // Generate suggestions
        _suggestions.value = generateSuggestions(newContext)
        
        return ContextualParseResult(
            originalInput = trimmed,
            resolvedInput = resolved,
            parseResult = parseResult,
            context = newContext,
            suggestions = _suggestions.value
        )
    }
    
    /**
     * Resolve pronoun references and context
     */
    private fun resolveReferences(input: String, context: ConversationContext): String {
        var resolved = input
        
        // Recipient references
        context.lastRecipient?.let { recipient ->
            resolved = resolved
                .replace(Regex("\\b(them|that wallet|that address|same address|same wallet)\\b", RegexOption.IGNORE_CASE), recipient)
                .replace(Regex("\\b(him|her)\\b", RegexOption.IGNORE_CASE), recipient)
        }
        
        // Token references
        context.lastToken?.let { token ->
            resolved = resolved
                .replace(Regex("\\b(that token|same token|it)\\b", RegexOption.IGNORE_CASE), token)
        }
        
        // Amount references
        context.lastAmount?.let { amount ->
            resolved = resolved
                .replace(Regex("\\b(same amount|that amount)\\b", RegexOption.IGNORE_CASE), amount.toPlainString())
        }
        
        // JITO tip references
        mevPreferences.defaultTip?.let { tip ->
            if (resolved.contains(Regex("(?:with\\s+)?(?:default|usual|normal)\\s+tip", RegexOption.IGNORE_CASE))) {
                resolved = resolved.replace(
                    Regex("(?:with\\s+)?(?:default|usual|normal)\\s+tip", RegexOption.IGNORE_CASE),
                    "with $tip tip"
                )
            }
        }
        
        // Double references
        resolved = resolved.replace(Regex("\\bdouble\\s+(?:that|it)\\b", RegexOption.IGNORE_CASE)) {
            context.lastAmount?.multiply(BigDecimal(2))?.toPlainString() ?: it.value
        }
        
        // Half references
        resolved = resolved.replace(Regex("\\bhalf\\s+(?:of\\s+)?(?:that|it)\\b", RegexOption.IGNORE_CASE)) {
            context.lastAmount?.divide(BigDecimal(2))?.toPlainString() ?: it.value
        }
        
        return resolved
    }
    
    private fun isUndoCommand(input: String): Boolean =
        input.lowercase() in listOf("undo", "go back", "revert", "cancel that")
    
    private fun isRedoCommand(input: String): Boolean =
        input.lowercase() in listOf("redo", "restore", "put it back")
    
    private fun isRepeatCommand(input: String): Boolean =
        input.lowercase().matches(Regex("^(?:again|repeat|same|do (?:it |that )?again).*$"))
    
    private fun isCancelCommand(input: String): Boolean =
        input.lowercase() in listOf("cancel", "stop", "nevermind", "forget it")
    
    private fun isConfirmCommand(input: String): Boolean =
        input.lowercase() in listOf("yes", "confirm", "ok", "okay", "do it", "send it", "execute")
    
    private fun isModifyCommand(input: String): Boolean =
        input.lowercase().matches(Regex("^(?:make it|change to|change it to|set to|update to)\\s+.+$"))
    
    private fun isHelpCommand(input: String): Boolean =
        input.lowercase() in listOf("help", "what can i do", "commands", "options")
    
    private fun isMevSettingCommand(input: String): Boolean =
        input.lowercase().matches(Regex("^(?:set|use)\\s+(?:default\\s+)?(?:jito|mev)\\s+(?:tip|settings?).*$"))
    
    private suspend fun handleUndo(): ContextualParseResult {
        if (undoStack.isEmpty()) {
            return ContextualParseResult(
                originalInput = "undo",
                resolvedInput = "undo",
                parseResult = ParseResult.Failure("Nothing to undo", emptyList()),
                context = _context.value,
                suggestions = emptyList()
            )
        }
        
        val previous = undoStack.removeLast()
        redoStack.add(_context.value)
        _context.value = previous
        
        return ContextualParseResult(
            originalInput = "undo",
            resolvedInput = "undo",
            parseResult = ParseResult.Success(
                TransactionIntent.Unknown("Undo successful"),
                confidence = 1.0,
                entities = emptyMap()
            ),
            context = previous,
            suggestions = generateSuggestions(previous),
            wasSpecialCommand = true
        )
    }
    
    private suspend fun handleRedo(): ContextualParseResult {
        if (redoStack.isEmpty()) {
            return ContextualParseResult(
                originalInput = "redo",
                resolvedInput = "redo",
                parseResult = ParseResult.Failure("Nothing to redo", emptyList()),
                context = _context.value,
                suggestions = emptyList()
            )
        }
        
        val next = redoStack.removeLast()
        undoStack.add(_context.value)
        _context.value = next
        
        return ContextualParseResult(
            originalInput = "redo",
            resolvedInput = "redo",
            parseResult = ParseResult.Success(
                TransactionIntent.Unknown("Redo successful"),
                confidence = 1.0,
                entities = emptyMap()
            ),
            context = next,
            suggestions = generateSuggestions(next),
            wasSpecialCommand = true
        )
    }
    
    private suspend fun handleRepeat(): ContextualParseResult {
        val lastSuccess = conversationHistory
            .mapNotNull { it.result as? ParseResult.Success }
            .lastOrNull()
        
        return if (lastSuccess != null) {
            ContextualParseResult(
                originalInput = "repeat",
                resolvedInput = conversationHistory.last().resolved,
                parseResult = lastSuccess,
                context = _context.value,
                suggestions = emptyList(),
                wasSpecialCommand = true
            )
        } else {
            ContextualParseResult(
                originalInput = "repeat",
                resolvedInput = "repeat",
                parseResult = ParseResult.Failure("No previous command to repeat", emptyList()),
                context = _context.value,
                suggestions = emptyList()
            )
        }
    }
    
    private suspend fun handleCancel(): ContextualParseResult {
        val clearedContext = ConversationContext.empty()
        undoStack.add(_context.value)
        _context.value = clearedContext
        
        return ContextualParseResult(
            originalInput = "cancel",
            resolvedInput = "cancel",
            parseResult = ParseResult.Success(
                TransactionIntent.Unknown("Cancelled"),
                confidence = 1.0,
                entities = emptyMap()
            ),
            context = clearedContext,
            suggestions = emptyList(),
            wasSpecialCommand = true
        )
    }
    
    private suspend fun handleConfirm(): ContextualParseResult {
        val lastIntent = _context.value.lastIntent
        
        return if (lastIntent != null) {
            ContextualParseResult(
                originalInput = "confirm",
                resolvedInput = "confirm",
                parseResult = ParseResult.Success(lastIntent, confidence = 1.0, entities = emptyMap()),
                context = _context.value.copy(confirmed = true),
                suggestions = emptyList(),
                wasSpecialCommand = true,
                requiresExecution = true
            )
        } else {
            ContextualParseResult(
                originalInput = "confirm",
                resolvedInput = "confirm",
                parseResult = ParseResult.Failure("Nothing to confirm", emptyList()),
                context = _context.value,
                suggestions = emptyList()
            )
        }
    }
    
    private suspend fun handleModify(input: String): ContextualParseResult {
        val lastIntent = _context.value.lastIntent
            ?: return ContextualParseResult(
                originalInput = input,
                resolvedInput = input,
                parseResult = ParseResult.Failure("Nothing to modify", emptyList()),
                context = _context.value,
                suggestions = emptyList()
            )
        
        // Extract new value
        val newValue = input.replace(Regex("^(?:make it|change to|change it to|set to|update to)\\s+", RegexOption.IGNORE_CASE), "")
        
        // Try to modify the last intent
        val modifiedIntent = modifyIntent(lastIntent, newValue)
        
        return if (modifiedIntent != null) {
            val newContext = _context.value.withIntent(modifiedIntent, input)
            undoStack.add(_context.value)
            _context.value = newContext
            
            ContextualParseResult(
                originalInput = input,
                resolvedInput = input,
                parseResult = ParseResult.Success(modifiedIntent, confidence = 0.95, entities = emptyMap()),
                context = newContext,
                suggestions = generateSuggestions(newContext),
                wasSpecialCommand = true
            )
        } else {
            ContextualParseResult(
                originalInput = input,
                resolvedInput = input,
                parseResult = ParseResult.Failure("Could not modify with: $newValue", emptyList()),
                context = _context.value,
                suggestions = emptyList()
            )
        }
    }
    
    private fun modifyIntent(intent: TransactionIntent, newValue: String): TransactionIntent? {
        // Try to parse as amount
        val amount = newValue.toBigDecimalOrNull()
        if (amount != null) {
            return when (intent) {
                is TransactionIntent.TransferSol -> intent.copy(amount = amount)
                is TransactionIntent.TransferToken -> intent.copy(amount = amount)
                is TransactionIntent.Swap -> intent.copy(amount = amount)
                is TransactionIntent.PumpfunBuy -> intent.copy(solAmount = amount)
                is TransactionIntent.PumpfunSell -> intent.copy(tokenAmount = amount)
                else -> null
            }
        }
        
        // Try to parse as recipient
        if (newValue.endsWith(".sol") || newValue.length == 44) {
            return when (intent) {
                is TransactionIntent.TransferSol -> intent.copy(recipient = newValue)
                is TransactionIntent.TransferToken -> intent.copy(recipient = newValue)
                else -> null
            }
        }
        
        // Try to parse as JITO tip
        val tipMatch = Regex("(\\d+)\\s*(?:k\\s*)?tip", RegexOption.IGNORE_CASE).find(newValue)
        if (tipMatch != null) {
            val tip = tipMatch.groupValues[1].toLong() * if (newValue.contains("k", ignoreCase = true)) 1000 else 1
            mevPreferences.lastTip = tip
            // Intent unchanged but MEV preference updated
            return intent
        }
        
        return null
    }
    
    private suspend fun handleHelp(): ContextualParseResult {
        val helpText = buildString {
            appendLine("Available commands:")
            appendLine("• Send SOL: \"send 1 SOL to alice.sol\"")
            appendLine("• Swap: \"swap 10 USDC for SOL with JITO\"")
            appendLine("• PumpFun: \"buy 0.5 SOL of BONK on pumpfun\"")
            appendLine("• JITO bundle: \"bundle: swap and send\"")
            appendLine("• Copy trade: \"copy wallet xyz with 2x\"")
            appendLine("• Subscribe: \"watch wallet updates\"")
            appendLine("• Modify: \"make it 100\" or \"change to 50\"")
            appendLine("• Repeat: \"again\" or \"repeat\"")
            appendLine("• Undo: \"undo\" or \"go back\"")
            appendLine("• Cancel: \"cancel\" or \"nevermind\"")
            appendLine("• MEV: \"set default JITO tip 50k\"")
        }
        
        return ContextualParseResult(
            originalInput = "help",
            resolvedInput = "help",
            parseResult = ParseResult.Success(
                TransactionIntent.Unknown(helpText),
                confidence = 1.0,
                entities = emptyMap()
            ),
            context = _context.value,
            suggestions = emptyList(),
            wasSpecialCommand = true
        )
    }
    
    private suspend fun handleMevSetting(input: String): ContextualParseResult {
        val tipMatch = Regex("(\\d+)\\s*(?:k)?", RegexOption.IGNORE_CASE).find(input)
        
        if (tipMatch != null) {
            val tip = tipMatch.groupValues[1].toLong() * if (input.contains("k", ignoreCase = true)) 1000 else 1
            mevPreferences.defaultTip = tip
            
            return ContextualParseResult(
                originalInput = input,
                resolvedInput = input,
                parseResult = ParseResult.Success(
                    TransactionIntent.Unknown("Default JITO tip set to $tip lamports"),
                    confidence = 1.0,
                    entities = emptyMap()
                ),
                context = _context.value,
                suggestions = emptyList(),
                wasSpecialCommand = true
            )
        }
        
        return ContextualParseResult(
            originalInput = input,
            resolvedInput = input,
            parseResult = ParseResult.Failure("Could not parse MEV setting", emptyList()),
            context = _context.value,
            suggestions = emptyList()
        )
    }
    
    private fun updatePreferences(intent: TransactionIntent) {
        when (intent) {
            is TransactionIntent.TransferSol -> {
                userPreferences.addFrequentRecipient(intent.recipient)
                userPreferences.addFrequentAmount(intent.amount)
            }
            is TransactionIntent.TransferToken -> {
                userPreferences.addFrequentRecipient(intent.recipient)
                userPreferences.addFrequentToken(intent.token)
                userPreferences.addFrequentAmount(intent.amount)
            }
            is TransactionIntent.Swap -> {
                userPreferences.addFrequentToken(intent.inputToken)
                userPreferences.addFrequentToken(intent.outputToken)
                if (intent.useJito) {
                    mevPreferences.incrementJitoUsage()
                }
            }
            is TransactionIntent.PumpfunBuy -> {
                userPreferences.addFrequentAmount(intent.solAmount)
                mevPreferences.incrementJitoUsage()
            }
            else -> {}
        }
        
        storage?.savePreferences(userPreferences, mevPreferences)
    }
    
    private fun generateSuggestions(context: ConversationContext): List<ContextualSuggestion> {
        val suggestions = mutableListOf<ContextualSuggestion>()
        
        // Based on recent activity
        context.lastIntent?.let { intent ->
            when (intent) {
                is TransactionIntent.TransferSol -> {
                    userPreferences.getFrequentRecipients().take(2).forEach { recipient ->
                        if (recipient != intent.recipient) {
                            suggestions.add(ContextualSuggestion(
                                text = "send ${intent.amount} SOL to $recipient",
                                type = SuggestionType.FREQUENT_RECIPIENT,
                                relevance = 0.8
                            ))
                        }
                    }
                }
                is TransactionIntent.Swap -> {
                    if (!intent.useJito && mevPreferences.jitoUsageRate() > 0.5) {
                        suggestions.add(ContextualSuggestion(
                            text = "swap with JITO protection",
                            type = SuggestionType.MEV_PROTECTION,
                            relevance = 0.9
                        ))
                    }
                }
                is TransactionIntent.PumpfunBuy -> {
                    suggestions.add(ContextualSuggestion(
                        text = "set stop loss at 50%",
                        type = SuggestionType.RISK_MANAGEMENT,
                        relevance = 0.7
                    ))
                }
                else -> {}
            }
        }
        
        // Time-based suggestions
        val hour = java.time.LocalTime.now().hour
        if (hour in 9..17) {
            suggestions.add(ContextualSuggestion(
                text = "check JITO tip floor",
                type = SuggestionType.MEV_INFO,
                relevance = 0.5
            ))
        }
        
        return suggestions.sortedByDescending { it.relevance }.take(5)
    }
    
    private fun loadPreferences(storage: MemoryStorage) {
        storage.loadPreferences()?.let { (user, mev) ->
            userPreferences.mergeFrom(user)
            mevPreferences.mergeFrom(mev)
        }
    }
    
    fun clearHistory() {
        conversationHistory.clear()
        undoStack.clear()
        redoStack.clear()
        _context.value = ConversationContext.empty()
    }
    
    fun getConversationHistory(): List<ConversationTurn> = conversationHistory.toList()
}

// === Context & State ===

data class ConversationContext(
    val lastIntent: TransactionIntent? = null,
    val lastInput: String? = null,
    val lastRecipient: String? = null,
    val lastToken: String? = null,
    val lastAmount: BigDecimal? = null,
    val lastError: String? = null,
    val confirmed: Boolean = false,
    val turnCount: Int = 0,
    val mevEnabled: Boolean = true,
    val defaultJitoTip: Long? = null
) {
    companion object {
        fun empty() = ConversationContext()
    }
    
    fun withIntent(intent: TransactionIntent, input: String): ConversationContext {
        return copy(
            lastIntent = intent,
            lastInput = input,
            lastRecipient = extractRecipient(intent) ?: lastRecipient,
            lastToken = extractToken(intent) ?: lastToken,
            lastAmount = extractAmount(intent) ?: lastAmount,
            lastError = null,
            confirmed = false,
            turnCount = turnCount + 1
        )
    }
    
    private fun extractRecipient(intent: TransactionIntent): String? {
        return when (intent) {
            is TransactionIntent.TransferSol -> intent.resolvedAddress ?: intent.recipient
            is TransactionIntent.TransferToken -> intent.resolvedAddress ?: intent.recipient
            else -> null
        }
    }
    
    private fun extractToken(intent: TransactionIntent): String? {
        return when (intent) {
            is TransactionIntent.TransferToken -> intent.token
            is TransactionIntent.Swap -> intent.outputToken
            is TransactionIntent.PumpfunBuy -> intent.tokenMint
            is TransactionIntent.PumpfunSell -> intent.tokenMint
            else -> null
        }
    }
    
    private fun extractAmount(intent: TransactionIntent): BigDecimal? {
        return when (intent) {
            is TransactionIntent.TransferSol -> intent.amount
            is TransactionIntent.TransferToken -> intent.amount
            is TransactionIntent.Swap -> intent.amount
            is TransactionIntent.PumpfunBuy -> intent.solAmount
            is TransactionIntent.PumpfunSell -> intent.tokenAmount
            else -> null
        }
    }
}

data class ConversationTurn(
    val input: String,
    val resolved: String,
    val result: ParseResult,
    val timestamp: Long
)

data class ContextualParseResult(
    val originalInput: String,
    val resolvedInput: String,
    val parseResult: ParseResult,
    val context: ConversationContext,
    val suggestions: List<ContextualSuggestion>,
    val wasSpecialCommand: Boolean = false,
    val requiresExecution: Boolean = false
)

data class ContextualSuggestion(
    val text: String,
    val type: SuggestionType,
    val relevance: Double
)

enum class SuggestionType {
    FREQUENT_RECIPIENT,
    FREQUENT_TOKEN,
    FREQUENT_AMOUNT,
    MEV_PROTECTION,
    MEV_INFO,
    RISK_MANAGEMENT,
    CONTINUATION,
    OPTIMIZATION
}

// === Preferences ===

class UserPreferences {
    private val frequentRecipients = ConcurrentHashMap<String, Int>()
    private val frequentTokens = ConcurrentHashMap<String, Int>()
    private val frequentAmounts = ConcurrentHashMap<BigDecimal, Int>()
    
    fun addFrequentRecipient(recipient: String) {
        frequentRecipients.compute(recipient) { _, v -> (v ?: 0) + 1 }
    }
    
    fun addFrequentToken(token: String) {
        frequentTokens.compute(token) { _, v -> (v ?: 0) + 1 }
    }
    
    fun addFrequentAmount(amount: BigDecimal) {
        frequentAmounts.compute(amount) { _, v -> (v ?: 0) + 1 }
    }
    
    fun getFrequentRecipients(): List<String> =
        frequentRecipients.entries.sortedByDescending { it.value }.map { it.key }
    
    fun getFrequentTokens(): List<String> =
        frequentTokens.entries.sortedByDescending { it.value }.map { it.key }
    
    fun getFrequentAmounts(): List<BigDecimal> =
        frequentAmounts.entries.sortedByDescending { it.value }.map { it.key }
    
    fun mergeFrom(other: UserPreferences) {
        other.frequentRecipients.forEach { (k, v) ->
            frequentRecipients.compute(k) { _, existing -> (existing ?: 0) + v }
        }
        other.frequentTokens.forEach { (k, v) ->
            frequentTokens.compute(k) { _, existing -> (existing ?: 0) + v }
        }
        other.frequentAmounts.forEach { (k, v) ->
            frequentAmounts.compute(k) { _, existing -> (existing ?: 0) + v }
        }
    }
}

class MevPreferences {
    var defaultTip: Long? = null
    var lastTip: Long? = null
    private var jitoUsageCount: Int = 0
    private var totalSwapCount: Int = 0
    
    fun incrementJitoUsage() {
        jitoUsageCount++
        totalSwapCount++
    }
    
    fun incrementNonJitoUsage() {
        totalSwapCount++
    }
    
    fun jitoUsageRate(): Double =
        if (totalSwapCount > 0) jitoUsageCount.toDouble() / totalSwapCount else 0.0
    
    fun mergeFrom(other: MevPreferences) {
        if (defaultTip == null) defaultTip = other.defaultTip
        jitoUsageCount += other.jitoUsageCount
        totalSwapCount += other.totalSwapCount
    }
}

// === Configuration ===

data class MemoryConfig(
    val maxHistorySize: Int = 100,
    val maxUndoSize: Int = 20,
    val persistPreferences: Boolean = true
)

// === Storage Interface ===

interface MemoryStorage {
    fun savePreferences(user: UserPreferences, mev: MevPreferences)
    fun loadPreferences(): Pair<UserPreferences, MevPreferences>?
    fun saveHistory(history: List<ConversationTurn>)
    fun loadHistory(): List<ConversationTurn>
}
