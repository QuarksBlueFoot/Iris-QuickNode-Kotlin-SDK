package xyz.selenus.iris.nlp.chain

import xyz.selenus.iris.nlp.NaturalLanguageBuilder
import xyz.selenus.iris.nlp.ParseResult
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.regex.Pattern

/**
 * Iris Intent Chain Parser - QuickNode Enhanced
 * 
 * Supports complex multi-intent parsing with:
 * - Sequential chaining: "send 1 SOL to alice then buy BONK on pumpfun"
 * - Conditional execution: "if SOL > $100 then sell all"
 * - MEV-protected batches: "bundle: swap USDC to SOL and buy BONK"
 * - JITO tip automation: "send with 50k tip"
 * - PumpFun operations: "snipe token launch"
 * - Yellowstone subscriptions: "watch wallet updates"
 * 
 * Solana Mobile Standard compliant with KMM 2026 architecture
 */
class IrisChainParser(
    private val nlpBuilder: NaturalLanguageBuilder
) {
    
    companion object {
        // Sequential patterns
        private val AND_THEN_PATTERN = Pattern.compile(
            "\\s*(?:and then|then|,\\s*then|→|->|;)\\s*",
            Pattern.CASE_INSENSITIVE
        )
        
        // Conditional patterns
        private val IF_THEN_PATTERN = Pattern.compile(
            "^(?:if|when|once)\\s+(.+?)\\s+(?:then|,)\\s+(.+?)$",
            Pattern.CASE_INSENSITIVE
        )
        
        // Price condition
        private val PRICE_CONDITION = Pattern.compile(
            "(?:price of\\s+)?([A-Z]+)\\s*(>|<|>=|<=|==|is above|is below|reaches|drops to)\\s*\\$?([\\d.]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Balance condition
        private val BALANCE_CONDITION = Pattern.compile(
            "(?:my\\s+)?([A-Z]+)\\s+balance\\s*(>|<|>=|<=|==)\\s*([\\d.]+)",
            Pattern.CASE_INSENSITIVE
        )
        
        // Time condition
        private val TIME_CONDITION = Pattern.compile(
            "(?:time\\s+is\\s+)?(?:at\\s+)?(\\d{1,2}(?::\\d{2})?\\s*(?:am|pm)?)",
            Pattern.CASE_INSENSITIVE
        )
        
        // JITO bundle pattern
        private val JITO_BUNDLE_PATTERN = Pattern.compile(
            "^(?:bundle|jito bundle|mev protect):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
        )
        
        // JITO tip pattern
        private val JITO_TIP_PATTERN = Pattern.compile(
            "(?:with|tip)\\s+(\\d+)\\s*(?:lamports?|k\\s*tip)?",
            Pattern.CASE_INSENSITIVE
        )
        
        // PumpFun snipe pattern
        private val PUMPFUN_SNIPE_PATTERN = Pattern.compile(
            "(?:snipe|front-?run|ape)\\s+(?:token\\s+)?(?:launch\\s+)?(?:of\\s+)?(.+?)(?:\\s+with\\s+([\\d.]+)\\s*sol)?",
            Pattern.CASE_INSENSITIVE
        )
        
        // Batch pattern
        private val BATCH_PATTERN = Pattern.compile(
            "^(?:batch|multi):\\s*(.+)$",
            Pattern.CASE_INSENSITIVE
        )
        
        // Schedule patterns
        private val SCHEDULE_PATTERN = Pattern.compile(
            "(.+?)\\s+(?:at|on|tomorrow|next|in)\\s+(.+?)$",
            Pattern.CASE_INSENSITIVE
        )
        
        // Recurring patterns
        private val RECURRING_PATTERN = Pattern.compile(
            "^(?:every|each)\\s+(\\w+)\\s+(.+?)$",
            Pattern.CASE_INSENSITIVE
        )
        
        // Cancel subscription pattern
        private val CANCEL_PATTERN = Pattern.compile(
            "^(?:cancel|stop|unsubscribe)\\s+(?:subscription|watching|monitoring)\\s*(.+)?$",
            Pattern.CASE_INSENSITIVE
        )
        
        // Copy trading pattern
        private val COPY_TRADE_PATTERN = Pattern.compile(
            "^(?:copy|mirror|follow)\\s+(?:trades?\\s+)?(?:of\\s+)?(?:wallet\\s+)?(.+?)(?:\\s+with\\s+([\\d.]+)\\s*(?:x|sol|%|percent))?$",
            Pattern.CASE_INSENSITIVE
        )
    }
    
    /**
     * Parse a complex input that may contain chains, conditions, or bundles
     */
    fun parse(input: String): ChainParseResult {
        val trimmed = input.trim()
        
        // Check for JITO bundle
        JITO_BUNDLE_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return parseJitoBundle(m.group(1))
            }
        }
        
        // Check for batch
        BATCH_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return parseBatch(m.group(1))
            }
        }
        
        // Check for conditional
        IF_THEN_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return parseConditional(m.group(1), m.group(2))
            }
        }
        
        // Check for recurring
        RECURRING_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return parseRecurring(m.group(1), m.group(2))
            }
        }
        
        // Check for schedule
        SCHEDULE_PATTERN.matcher(trimmed).let { m ->
            if (m.find() && isScheduleContext(m.group(2))) {
                return parseScheduled(m.group(1), m.group(2))
            }
        }
        
        // Check for cancel
        CANCEL_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return ChainParseResult.CancelSubscription(m.group(1)?.trim())
            }
        }
        
        // Check for copy trading
        COPY_TRADE_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return parseCopyTrade(m.group(1), m.group(2))
            }
        }
        
        // Check for PumpFun snipe
        PUMPFUN_SNIPE_PATTERN.matcher(trimmed).let { m ->
            if (m.find()) {
                return parseSnipe(m.group(1), m.group(2))
            }
        }
        
        // Check for chain
        if (AND_THEN_PATTERN.matcher(trimmed).find()) {
            return parseSequential(trimmed)
        }
        
        // Single intent with optional JITO tip
        return parseSingleWithOptions(trimmed)
    }
    
    private fun parseJitoBundle(content: String): ChainParseResult {
        val parts = AND_THEN_PATTERN.split(content)
        val intents = mutableListOf<ParseResult>()
        
        for (part in parts) {
            val result = nlpBuilder.parse(part.trim())
            intents.add(result)
        }
        
        val successful = intents.filterIsInstance<ParseResult.Success>()
        
        return if (successful.size == intents.size) {
            ChainParseResult.JitoBundle(
                intents = successful,
                tip = extractJitoTip(content)
            )
        } else {
            val failures = intents.filterIsInstance<ParseResult.Failure>()
            ChainParseResult.PartialFailure(successful, failures)
        }
    }
    
    private fun parseBatch(content: String): ChainParseResult {
        val parts = content.split(",", " and ")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        
        val intents = parts.map { nlpBuilder.parse(it) }
        val successful = intents.filterIsInstance<ParseResult.Success>()
        
        return if (successful.size == intents.size) {
            ChainParseResult.Batch(successful)
        } else {
            val failures = intents.filterIsInstance<ParseResult.Failure>()
            ChainParseResult.PartialFailure(successful, failures)
        }
    }
    
    private fun parseSequential(input: String): ChainParseResult {
        val parts = AND_THEN_PATTERN.split(input)
        val chain = mutableListOf<ChainStep>()
        
        for ((index, part) in parts.withIndex()) {
            val trimmedPart = part.trim()
            if (trimmedPart.isBlank()) continue
            
            val result = nlpBuilder.parse(trimmedPart)
            
            when (result) {
                is ParseResult.Success -> {
                    chain.add(ChainStep(
                        order = index,
                        parseResult = result,
                        dependsOn = if (index > 0) index - 1 else null
                    ))
                }
                is ParseResult.Failure -> {
                    return ChainParseResult.ChainFailure(
                        failedStep = index,
                        reason = result.reason,
                        suggestions = result.suggestions
                    )
                }
                is ParseResult.Ambiguous -> {
                    return ChainParseResult.ChainAmbiguous(
                        ambiguousStep = index,
                        candidates = result.candidates
                    )
                }
            }
        }
        
        return if (chain.isEmpty()) {
            ChainParseResult.Empty
        } else {
            ChainParseResult.Sequential(chain)
        }
    }
    
    private fun parseConditional(conditionText: String, actionText: String): ChainParseResult {
        val condition = parseCondition(conditionText)
            ?: return ChainParseResult.InvalidCondition(conditionText)
        
        val action = nlpBuilder.parse(actionText)
        
        return when (action) {
            is ParseResult.Success -> ChainParseResult.Conditional(
                condition = condition,
                thenIntent = action
            )
            is ParseResult.Failure -> ChainParseResult.ChainFailure(
                failedStep = 0,
                reason = action.reason,
                suggestions = action.suggestions
            )
            is ParseResult.Ambiguous -> ChainParseResult.ChainAmbiguous(
                ambiguousStep = 0,
                candidates = action.candidates
            )
        }
    }
    
    private fun parseCondition(text: String): Condition? {
        // Price condition
        PRICE_CONDITION.matcher(text).let { m ->
            if (m.find()) {
                val token = m.group(1).uppercase()
                val operator = parseOperator(m.group(2))
                val value = BigDecimal(m.group(3))
                return Condition.Price(token, operator, value)
            }
        }
        
        // Balance condition
        BALANCE_CONDITION.matcher(text).let { m ->
            if (m.find()) {
                val token = m.group(1).uppercase()
                val operator = parseOperator(m.group(2))
                val amount = BigDecimal(m.group(3))
                return Condition.Balance(token, operator, amount)
            }
        }
        
        // Time condition
        TIME_CONDITION.matcher(text).let { m ->
            if (m.find()) {
                val time = parseTimeString(m.group(1))
                return Condition.Time(time)
            }
        }
        
        return null
    }
    
    private fun parseOperator(op: String): ComparisonOperator {
        return when (op.lowercase()) {
            ">", "is above" -> ComparisonOperator.GREATER_THAN
            "<", "is below", "drops to" -> ComparisonOperator.LESS_THAN
            ">=", "reaches" -> ComparisonOperator.GREATER_EQUAL
            "<=" -> ComparisonOperator.LESS_EQUAL
            "==", "=" -> ComparisonOperator.EQUALS
            else -> ComparisonOperator.EQUALS
        }
    }
    
    private fun parseRecurring(interval: String, action: String): ChainParseResult {
        val recurringInterval = parseRecurringInterval(interval)
            ?: return ChainParseResult.InvalidRecurrence(interval)
        
        val intent = nlpBuilder.parse(action)
        
        return when (intent) {
            is ParseResult.Success -> ChainParseResult.Recurring(
                interval = recurringInterval,
                intent = intent,
                startTime = LocalDateTime.now()
            )
            is ParseResult.Failure -> ChainParseResult.ChainFailure(
                failedStep = 0,
                reason = intent.reason,
                suggestions = intent.suggestions
            )
            is ParseResult.Ambiguous -> ChainParseResult.ChainAmbiguous(
                ambiguousStep = 0,
                candidates = intent.candidates
            )
        }
    }
    
    private fun parseRecurringInterval(text: String): RecurringInterval? {
        return when (text.lowercase()) {
            "hour", "hourly" -> RecurringInterval.Hourly
            "day", "daily" -> RecurringInterval.Daily
            "week", "weekly" -> RecurringInterval.Weekly
            "month", "monthly" -> RecurringInterval.Monthly
            "minute" -> RecurringInterval.Custom(60_000L)
            else -> {
                // Try to parse "X hours/days/etc"
                val pattern = Pattern.compile("(\\d+)\\s*(hour|day|week|minute)s?", Pattern.CASE_INSENSITIVE)
                pattern.matcher(text).let { m ->
                    if (m.find()) {
                        val count = m.group(1).toLong()
                        val unit = m.group(2).lowercase()
                        val ms = when (unit) {
                            "minute" -> count * 60_000L
                            "hour" -> count * 3_600_000L
                            "day" -> count * 86_400_000L
                            "week" -> count * 604_800_000L
                            else -> null
                        }
                        ms?.let { RecurringInterval.Custom(it) }
                    } else null
                }
            }
        }
    }
    
    private fun parseScheduled(action: String, scheduleText: String): ChainParseResult {
        val scheduledTime = parseScheduleTime(scheduleText)
            ?: return ChainParseResult.InvalidSchedule(scheduleText)
        
        val intent = nlpBuilder.parse(action)
        
        return when (intent) {
            is ParseResult.Success -> ChainParseResult.Scheduled(
                intent = intent,
                executionTime = scheduledTime
            )
            is ParseResult.Failure -> ChainParseResult.ChainFailure(
                failedStep = 0,
                reason = intent.reason,
                suggestions = intent.suggestions
            )
            is ParseResult.Ambiguous -> ChainParseResult.ChainAmbiguous(
                ambiguousStep = 0,
                candidates = intent.candidates
            )
        }
    }
    
    private fun parseScheduleTime(text: String): LocalDateTime? {
        val lower = text.lowercase().trim()
        val now = LocalDateTime.now()
        
        return when {
            lower == "tomorrow" -> now.plusDays(1)
            lower == "next week" -> now.plusWeeks(1)
            lower.startsWith("tomorrow at ") -> {
                val time = parseTimeString(lower.removePrefix("tomorrow at "))
                now.plusDays(1).with(time)
            }
            lower.startsWith("in ") -> parseRelativeTime(lower.removePrefix("in "), now)
            lower.startsWith("next ") -> parseNextDayOfWeek(lower.removePrefix("next "), now)
            else -> parseAbsoluteTime(text)
        }
    }
    
    private fun parseRelativeTime(text: String, base: LocalDateTime): LocalDateTime? {
        val pattern = Pattern.compile("(\\d+)\\s*(minute|hour|day|week)s?", Pattern.CASE_INSENSITIVE)
        pattern.matcher(text).let { m ->
            if (m.find()) {
                val count = m.group(1).toLong()
                return when (m.group(2).lowercase()) {
                    "minute" -> base.plusMinutes(count)
                    "hour" -> base.plusHours(count)
                    "day" -> base.plusDays(count)
                    "week" -> base.plusWeeks(count)
                    else -> null
                }
            }
        }
        return null
    }
    
    private fun parseNextDayOfWeek(text: String, base: LocalDateTime): LocalDateTime? {
        val dayOfWeek = when (text.lowercase().trim()) {
            "monday" -> DayOfWeek.MONDAY
            "tuesday" -> DayOfWeek.TUESDAY
            "wednesday" -> DayOfWeek.WEDNESDAY
            "thursday" -> DayOfWeek.THURSDAY
            "friday" -> DayOfWeek.FRIDAY
            "saturday" -> DayOfWeek.SATURDAY
            "sunday" -> DayOfWeek.SUNDAY
            else -> return null
        }
        
        var next = base.toLocalDate()
        while (next.dayOfWeek != dayOfWeek) {
            next = next.plusDays(1)
        }
        if (next == base.toLocalDate()) {
            next = next.plusWeeks(1)
        }
        
        return next.atTime(base.toLocalTime())
    }
    
    private fun parseAbsoluteTime(text: String): LocalDateTime? {
        val formats = listOf(
            "yyyy-MM-dd HH:mm",
            "MM/dd/yyyy HH:mm",
            "HH:mm"
        )
        
        for (format in formats) {
            try {
                val formatter = DateTimeFormatter.ofPattern(format)
                return if (format == "HH:mm") {
                    LocalDateTime.of(LocalDate.now(), LocalTime.parse(text, formatter))
                } else {
                    LocalDateTime.parse(text, formatter)
                }
            } catch (e: Exception) {
                continue
            }
        }
        
        return null
    }
    
    private fun parseTimeString(text: String): LocalTime {
        val lower = text.lowercase().trim()
        
        // Handle 12-hour format
        val pattern = Pattern.compile("(\\d{1,2})(?::(\\d{2}))?\\s*(am|pm)?", Pattern.CASE_INSENSITIVE)
        pattern.matcher(lower).let { m ->
            if (m.find()) {
                var hour = m.group(1).toInt()
                val minute = m.group(2)?.toInt() ?: 0
                val ampm = m.group(3)?.lowercase()
                
                if (ampm == "pm" && hour < 12) hour += 12
                if (ampm == "am" && hour == 12) hour = 0
                
                return LocalTime.of(hour, minute)
            }
        }
        
        return LocalTime.parse(text)
    }
    
    private fun isScheduleContext(text: String): Boolean {
        val keywords = listOf(
            "tomorrow", "next", "in ", "at ", "on ",
            "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday",
            "am", "pm", ":"
        )
        return keywords.any { text.lowercase().contains(it) }
    }
    
    private fun parseCopyTrade(walletText: String, multiplierText: String?): ChainParseResult {
        val wallet = walletText.trim()
        val multiplier = multiplierText?.let {
            val num = it.replace(Regex("[^\\d.]"), "")
            BigDecimal(num)
        } ?: BigDecimal.ONE
        
        return ChainParseResult.CopyTrade(
            sourceWallet = wallet,
            multiplier = multiplier,
            subscriptionId = null
        )
    }
    
    private fun parseSnipe(tokenInfo: String, solAmountText: String?): ChainParseResult {
        val solAmount = solAmountText?.let { BigDecimal(it) }
        
        return ChainParseResult.PumpfunSnipe(
            tokenIdentifier = tokenInfo.trim(),
            solAmount = solAmount,
            useJito = true
        )
    }
    
    private fun parseSingleWithOptions(input: String): ChainParseResult {
        val tip = extractJitoTip(input)
        val cleanInput = if (tip != null) {
            input.replace(JITO_TIP_PATTERN.toRegex(), "").trim()
        } else {
            input
        }
        
        val result = nlpBuilder.parse(cleanInput)
        
        return when (result) {
            is ParseResult.Success -> ChainParseResult.Single(
                intent = result,
                jitoTip = tip
            )
            is ParseResult.Failure -> ChainParseResult.ChainFailure(
                failedStep = 0,
                reason = result.reason,
                suggestions = result.suggestions
            )
            is ParseResult.Ambiguous -> ChainParseResult.ChainAmbiguous(
                ambiguousStep = 0,
                candidates = result.candidates
            )
        }
    }
    
    private fun extractJitoTip(input: String): Long? {
        JITO_TIP_PATTERN.matcher(input).let { m ->
            if (m.find()) {
                val value = m.group(1).toLong()
                // Handle "k tip" notation (e.g., "50k tip" = 50000)
                return if (input.lowercase().contains("k")) value * 1000 else value
            }
        }
        return null
    }
}

// === Chain Parse Results ===

sealed class ChainParseResult {
    
    data object Empty : ChainParseResult()
    
    data class Single(
        val intent: ParseResult.Success,
        val jitoTip: Long? = null
    ) : ChainParseResult()
    
    data class Sequential(
        val steps: List<ChainStep>
    ) : ChainParseResult() {
        val size: Int get() = steps.size
        fun first(): ChainStep = steps.first()
        fun last(): ChainStep = steps.last()
    }
    
    data class JitoBundle(
        val intents: List<ParseResult.Success>,
        val tip: Long? = null
    ) : ChainParseResult() {
        val size: Int get() = intents.size
    }
    
    data class Batch(
        val intents: List<ParseResult.Success>
    ) : ChainParseResult() {
        val size: Int get() = intents.size
    }
    
    data class Conditional(
        val condition: Condition,
        val thenIntent: ParseResult.Success,
        val elseIntent: ParseResult.Success? = null
    ) : ChainParseResult()
    
    data class Scheduled(
        val intent: ParseResult.Success,
        val executionTime: LocalDateTime
    ) : ChainParseResult() {
        fun getDelayMs(): Long {
            val now = LocalDateTime.now()
            return java.time.Duration.between(now, executionTime).toMillis()
        }
    }
    
    data class Recurring(
        val interval: RecurringInterval,
        val intent: ParseResult.Success,
        val startTime: LocalDateTime = LocalDateTime.now(),
        val endTime: LocalDateTime? = null
    ) : ChainParseResult()
    
    data class CopyTrade(
        val sourceWallet: String,
        val multiplier: BigDecimal = BigDecimal.ONE,
        val subscriptionId: String? = null
    ) : ChainParseResult()
    
    data class PumpfunSnipe(
        val tokenIdentifier: String,
        val solAmount: BigDecimal? = null,
        val useJito: Boolean = true
    ) : ChainParseResult()
    
    data class CancelSubscription(
        val subscriptionId: String?
    ) : ChainParseResult()
    
    data class ChainFailure(
        val failedStep: Int,
        val reason: String,
        val suggestions: List<String>
    ) : ChainParseResult()
    
    data class ChainAmbiguous(
        val ambiguousStep: Int,
        val candidates: List<ParseResult.Success>
    ) : ChainParseResult()
    
    data class PartialFailure(
        val successful: List<ParseResult.Success>,
        val failed: List<ParseResult.Failure>
    ) : ChainParseResult()
    
    data class InvalidCondition(
        val conditionText: String
    ) : ChainParseResult()
    
    data class InvalidSchedule(
        val scheduleText: String
    ) : ChainParseResult()
    
    data class InvalidRecurrence(
        val intervalText: String
    ) : ChainParseResult()
}

// === Chain Step ===

data class ChainStep(
    val order: Int,
    val parseResult: ParseResult.Success,
    val dependsOn: Int? = null
) {
    val intent get() = parseResult.intent
}

// === Conditions ===

sealed class Condition {
    abstract fun describe(): String
    
    data class Price(
        val token: String,
        val operator: ComparisonOperator,
        val targetPrice: BigDecimal
    ) : Condition() {
        override fun describe(): String = "$token price ${operator.symbol} $$targetPrice"
    }
    
    data class Balance(
        val token: String,
        val operator: ComparisonOperator,
        val targetAmount: BigDecimal
    ) : Condition() {
        override fun describe(): String = "$token balance ${operator.symbol} $targetAmount"
    }
    
    data class Time(
        val targetTime: LocalTime
    ) : Condition() {
        override fun describe(): String = "time is $targetTime"
    }
    
    data class SlotReached(
        val slot: Long
    ) : Condition() {
        override fun describe(): String = "slot reaches $slot"
    }
    
    data class AccountChanged(
        val address: String
    ) : Condition() {
        override fun describe(): String = "account $address changes"
    }
}

enum class ComparisonOperator(val symbol: String) {
    GREATER_THAN(">"),
    LESS_THAN("<"),
    GREATER_EQUAL(">="),
    LESS_EQUAL("<="),
    EQUALS("==")
}

// === Recurring Intervals ===

sealed class RecurringInterval {
    abstract val intervalMs: Long
    
    data object Hourly : RecurringInterval() {
        override val intervalMs: Long = 3_600_000L
    }
    
    data object Daily : RecurringInterval() {
        override val intervalMs: Long = 86_400_000L
    }
    
    data object Weekly : RecurringInterval() {
        override val intervalMs: Long = 604_800_000L
    }
    
    data object Monthly : RecurringInterval() {
        override val intervalMs: Long = 2_592_000_000L // ~30 days
    }
    
    data class Custom(override val intervalMs: Long) : RecurringInterval()
}
