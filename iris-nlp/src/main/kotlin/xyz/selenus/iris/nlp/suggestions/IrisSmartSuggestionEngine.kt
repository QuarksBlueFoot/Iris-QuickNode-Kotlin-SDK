package xyz.selenus.iris.nlp.suggestions

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import xyz.selenus.iris.nlp.ParseResult
import xyz.selenus.iris.nlp.TransactionIntent
import java.math.BigDecimal
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.concurrent.ConcurrentHashMap

/**
 * Iris Smart Suggestion Engine - QuickNode Enhanced
 * 
 * AI-powered suggestion engine with:
 * - Pattern-based suggestions from user history
 * - Time-based suggestions (recurring behaviors)
 * - Portfolio-based suggestions (rebalancing, staking)
 * - MEV optimization suggestions (JITO tips, timing)
 * - PumpFun trending token suggestions
 * - Yellowstone subscription suggestions
 * - Gas/priority fee optimization
 * 
 * Solana Mobile Standard compliant with KMM 2026 architecture
 */
class IrisSmartSuggestionEngine(
    private val portfolioProvider: PortfolioProvider,
    private val trendProvider: TrendProvider,
    private val mevProvider: MevDataProvider,
    private val config: SuggestionConfig = SuggestionConfig()
) {
    
    private val _suggestions = MutableStateFlow<List<SmartSuggestion>>(emptyList())
    val suggestions: StateFlow<List<SmartSuggestion>> = _suggestions.asStateFlow()
    
    private val userPatterns = UserPatternAnalyzer()
    private val mevAnalyzer = MevPatternAnalyzer()
    private val timePatterns = TimePatternAnalyzer()
    
    /**
     * Generate comprehensive suggestions
     */
    suspend fun generateSuggestions(): Flow<SuggestionBatch> = flow {
        val allSuggestions = mutableListOf<SmartSuggestion>()
        
        // MEV optimization suggestions
        val mevSuggestions = generateMevSuggestions()
        allSuggestions.addAll(mevSuggestions)
        emit(SuggestionBatch(SuggestionCategory.MEV_OPTIMIZATION, mevSuggestions))
        
        // Portfolio-based suggestions
        val portfolioSuggestions = generatePortfolioSuggestions()
        allSuggestions.addAll(portfolioSuggestions)
        emit(SuggestionBatch(SuggestionCategory.PORTFOLIO, portfolioSuggestions))
        
        // Trending/PumpFun suggestions
        val trendSuggestions = generateTrendSuggestions()
        allSuggestions.addAll(trendSuggestions)
        emit(SuggestionBatch(SuggestionCategory.TRENDING, trendSuggestions))
        
        // Pattern-based suggestions
        val patternSuggestions = generatePatternSuggestions()
        allSuggestions.addAll(patternSuggestions)
        emit(SuggestionBatch(SuggestionCategory.PATTERN, patternSuggestions))
        
        // Time-based suggestions
        val timeSuggestions = generateTimeSuggestions()
        allSuggestions.addAll(timeSuggestions)
        emit(SuggestionBatch(SuggestionCategory.TIME_BASED, timeSuggestions))
        
        // Yellowstone subscription suggestions
        val subscriptionSuggestions = generateSubscriptionSuggestions()
        allSuggestions.addAll(subscriptionSuggestions)
        emit(SuggestionBatch(SuggestionCategory.SUBSCRIPTIONS, subscriptionSuggestions))
        
        // Update global state
        _suggestions.value = allSuggestions
            .sortedByDescending { it.relevanceScore }
            .take(config.maxSuggestions)
    }
    
    private suspend fun generateMevSuggestions(): List<SmartSuggestion> {
        val suggestions = mutableListOf<SmartSuggestion>()
        
        // JITO tip floor suggestion
        val tipFloor = mevProvider.getJitoTipFloor()
        val avgTip = mevAnalyzer.getAverageUserTip()
        
        if (avgTip != null && avgTip < tipFloor * 0.8) {
            suggestions.add(SmartSuggestion(
                id = "mev_low_tip",
                text = "Your average JITO tip is below the current floor. Consider increasing to ${tipFloor * 1.2} for better landing rate.",
                intent = "set default JITO tip ${(tipFloor * 1.2).toLong()}",
                type = SuggestionType.MEV_TIP_OPTIMIZATION,
                relevanceScore = 0.95,
                urgency = SuggestionUrgency.HIGH,
                metadata = mapOf(
                    "currentAvg" to avgTip.toString(),
                    "tipFloor" to tipFloor.toString(),
                    "recommended" to (tipFloor * 1.2).toString()
                )
            ))
        }
        
        // Low congestion period
        val congestion = mevProvider.getNetworkCongestion()
        if (congestion < 0.3) {
            suggestions.add(SmartSuggestion(
                id = "mev_low_congestion",
                text = "Network congestion is low. Good time for swaps without JITO protection.",
                intent = null,
                type = SuggestionType.MEV_TIMING,
                relevanceScore = 0.7,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("congestion" to congestion.toString())
            ))
        }
        
        // High MEV activity warning
        val mevActivity = mevProvider.getMevActivityLevel()
        if (mevActivity > 0.8) {
            suggestions.add(SmartSuggestion(
                id = "mev_high_activity",
                text = "High MEV activity detected. Use JITO bundles for all swaps.",
                intent = "swap with JITO",
                type = SuggestionType.MEV_PROTECTION,
                relevanceScore = 0.9,
                urgency = SuggestionUrgency.MEDIUM,
                metadata = mapOf("mevLevel" to mevActivity.toString())
            ))
        }
        
        // Fastlane suggestion based on history
        if (!mevAnalyzer.usesFastlane() && mevAnalyzer.hasHighPriorityNeeds()) {
            suggestions.add(SmartSuggestion(
                id = "mev_fastlane",
                text = "Your transactions often need priority. Consider using Fastlane for faster confirmations.",
                intent = "enable Fastlane",
                type = SuggestionType.MEV_FASTLANE,
                relevanceScore = 0.75,
                urgency = SuggestionUrgency.LOW,
                metadata = emptyMap()
            ))
        }
        
        return suggestions
    }
    
    private suspend fun generatePortfolioSuggestions(): List<SmartSuggestion> {
        val suggestions = mutableListOf<SmartSuggestion>()
        val portfolio = portfolioProvider.getPortfolio()
        
        // Rebalancing suggestion
        val imbalance = portfolio.calculateImbalance()
        if (imbalance > config.rebalanceThreshold) {
            val rebalanceAction = portfolio.suggestRebalance()
            suggestions.add(SmartSuggestion(
                id = "portfolio_rebalance",
                text = "Portfolio is ${(imbalance * 100).toInt()}% off target. $rebalanceAction",
                intent = rebalanceAction,
                type = SuggestionType.REBALANCE,
                relevanceScore = 0.8,
                urgency = SuggestionUrgency.MEDIUM,
                metadata = mapOf("imbalance" to imbalance.toString())
            ))
        }
        
        // Staking opportunities
        val unstaked = portfolio.getUnstakedSol()
        if (unstaked > config.stakingSuggestionThreshold) {
            suggestions.add(SmartSuggestion(
                id = "portfolio_stake",
                text = "You have $unstaked SOL unstaked. Consider staking for passive yield.",
                intent = "stake $unstaked SOL",
                type = SuggestionType.STAKING_OPPORTUNITY,
                relevanceScore = 0.7,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("amount" to unstaked.toString())
            ))
        }
        
        // Low balance warning
        if (portfolio.solBalance < config.lowBalanceThreshold) {
            suggestions.add(SmartSuggestion(
                id = "portfolio_low_balance",
                text = "SOL balance is low (${portfolio.solBalance}). Consider topping up for gas.",
                intent = null,
                type = SuggestionType.LOW_BALANCE_WARNING,
                relevanceScore = 0.95,
                urgency = SuggestionUrgency.HIGH,
                metadata = mapOf("balance" to portfolio.solBalance.toString())
            ))
        }
        
        // Dust consolidation
        val dustTokens = portfolio.getDustTokens(config.dustThreshold)
        if (dustTokens.size >= 3) {
            suggestions.add(SmartSuggestion(
                id = "portfolio_dust",
                text = "You have ${dustTokens.size} dust tokens. Consolidate to SOL?",
                intent = "swap dust to SOL",
                type = SuggestionType.DUST_CONSOLIDATION,
                relevanceScore = 0.5,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("count" to dustTokens.size.toString())
            ))
        }
        
        // Take profit suggestion
        portfolio.tokens.forEach { token ->
            if (token.unrealizedPnlPercent > config.takeProfitThreshold) {
                suggestions.add(SmartSuggestion(
                    id = "portfolio_tp_${token.symbol}",
                    text = "${token.symbol} is up ${(token.unrealizedPnlPercent * 100).toInt()}%. Consider taking profit.",
                    intent = "sell ${token.balance / BigDecimal(2)} ${token.symbol}",
                    type = SuggestionType.TAKE_PROFIT,
                    relevanceScore = 0.85,
                    urgency = SuggestionUrgency.MEDIUM,
                    metadata = mapOf(
                        "token" to token.symbol,
                        "pnl" to token.unrealizedPnlPercent.toString()
                    )
                ))
            }
        }
        
        return suggestions
    }
    
    private suspend fun generateTrendSuggestions(): List<SmartSuggestion> {
        val suggestions = mutableListOf<SmartSuggestion>()
        
        // PumpFun trending tokens
        val trending = trendProvider.getPumpfunTrending()
        trending.take(3).forEach { token ->
            suggestions.add(SmartSuggestion(
                id = "trend_pf_${token.symbol}",
                text = "${token.name} (${token.symbol}) trending on PumpFun. +${(token.priceChange24h * 100).toInt()}%",
                intent = "buy ${config.defaultTrendAmount} SOL of ${token.symbol} on pumpfun",
                type = SuggestionType.PUMPFUN_TRENDING,
                relevanceScore = 0.6 + (token.volume24h / 1000000.0).coerceAtMost(0.3),
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf(
                    "symbol" to token.symbol,
                    "change" to token.priceChange24h.toString(),
                    "volume" to token.volume24h.toString()
                )
            ))
        }
        
        // Hot swaps
        val hotPairs = trendProvider.getHotSwapPairs()
        hotPairs.take(2).forEach { pair ->
            suggestions.add(SmartSuggestion(
                id = "trend_swap_${pair.inputToken}_${pair.outputToken}",
                text = "${pair.inputToken}/${pair.outputToken} is a hot swap pair. Volume: \$${formatVolume(pair.volume24h)}",
                intent = "swap ${pair.inputToken} for ${pair.outputToken}",
                type = SuggestionType.TRENDING_SWAP,
                relevanceScore = 0.5,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf(
                    "input" to pair.inputToken,
                    "output" to pair.outputToken,
                    "volume" to pair.volume24h.toString()
                )
            ))
        }
        
        // Token graduation (PumpFun -> Raydium)
        val graduating = trendProvider.getGraduatingTokens()
        graduating.forEach { token ->
            suggestions.add(SmartSuggestion(
                id = "trend_grad_${token.symbol}",
                text = "${token.symbol} is graduating to Raydium! ${token.progress}% complete.",
                intent = "watch ${token.address}",
                type = SuggestionType.TOKEN_GRADUATION,
                relevanceScore = 0.8,
                urgency = SuggestionUrgency.MEDIUM,
                metadata = mapOf(
                    "symbol" to token.symbol,
                    "progress" to token.progress.toString()
                )
            ))
        }
        
        return suggestions
    }
    
    private suspend fun generatePatternSuggestions(): List<SmartSuggestion> {
        val suggestions = mutableListOf<SmartSuggestion>()
        
        // Frequent recipients
        userPatterns.getFrequentRecipients().take(2).forEach { (recipient, count) ->
            suggestions.add(SmartSuggestion(
                id = "pattern_recipient_$recipient",
                text = "You often send to $recipient",
                intent = "send to $recipient",
                type = SuggestionType.FREQUENT_RECIPIENT,
                relevanceScore = 0.6 + (count / 20.0).coerceAtMost(0.2),
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("recipient" to recipient, "count" to count.toString())
            ))
        }
        
        // Frequent tokens
        userPatterns.getFrequentTokens().take(3).forEach { (token, count) ->
            suggestions.add(SmartSuggestion(
                id = "pattern_token_$token",
                text = "You frequently trade $token",
                intent = "swap SOL for $token",
                type = SuggestionType.FREQUENT_TOKEN,
                relevanceScore = 0.5 + (count / 30.0).coerceAtMost(0.3),
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("token" to token, "count" to count.toString())
            ))
        }
        
        // Recurring transaction patterns
        userPatterns.getRecurringPatterns().forEach { pattern ->
            suggestions.add(SmartSuggestion(
                id = "pattern_recurring_${pattern.id}",
                text = "You do this ${pattern.frequency}: ${pattern.description}",
                intent = pattern.intent,
                type = SuggestionType.RECURRING_PATTERN,
                relevanceScore = 0.75,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("frequency" to pattern.frequency)
            ))
        }
        
        return suggestions
    }
    
    private suspend fun generateTimeSuggestions(): List<SmartSuggestion> {
        val suggestions = mutableListOf<SmartSuggestion>()
        val now = LocalDateTime.now()
        val hour = now.hour
        val dayOfWeek = now.dayOfWeek
        
        // Time-based activity patterns
        val peakHours = timePatterns.getPeakActivityHours()
        if (hour in peakHours) {
            suggestions.add(SmartSuggestion(
                id = "time_peak_hour",
                text = "This is typically your active trading time",
                intent = null,
                type = SuggestionType.ACTIVITY_TIME,
                relevanceScore = 0.4,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("hour" to hour.toString())
            ))
        }
        
        // Day-specific patterns
        val dayPatterns = timePatterns.getDayPatterns(dayOfWeek)
        dayPatterns.forEach { pattern ->
            suggestions.add(SmartSuggestion(
                id = "time_day_${pattern.id}",
                text = "You usually ${pattern.description} on ${dayOfWeek}s",
                intent = pattern.intent,
                type = SuggestionType.DAY_PATTERN,
                relevanceScore = 0.6,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("day" to dayOfWeek.name)
            ))
        }
        
        // Low fee times
        if (hour in 2..6) {
            suggestions.add(SmartSuggestion(
                id = "time_low_fee",
                text = "Network fees are typically lower now. Good time for batch transactions.",
                intent = null,
                type = SuggestionType.LOW_FEE_TIME,
                relevanceScore = 0.5,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("hour" to hour.toString())
            ))
        }
        
        return suggestions
    }
    
    private suspend fun generateSubscriptionSuggestions(): List<SmartSuggestion> {
        val suggestions = mutableListOf<SmartSuggestion>()
        
        // Watch wallet suggestions based on trading partners
        userPatterns.getFrequentRecipients().take(1).forEach { (wallet, _) ->
            suggestions.add(SmartSuggestion(
                id = "sub_wallet_$wallet",
                text = "Subscribe to updates from $wallet?",
                intent = "watch wallet $wallet",
                type = SuggestionType.SUBSCRIPTION_WALLET,
                relevanceScore = 0.5,
                urgency = SuggestionUrgency.LOW,
                metadata = mapOf("wallet" to wallet)
            ))
        }
        
        // Token mint subscriptions for held tokens
        val portfolio = portfolioProvider.getPortfolio()
        portfolio.tokens
            .filter { it.balance > BigDecimal.ZERO }
            .take(2)
            .forEach { token ->
                suggestions.add(SmartSuggestion(
                    id = "sub_token_${token.symbol}",
                    text = "Subscribe to ${token.symbol} program updates?",
                    intent = "watch token ${token.mint}",
                    type = SuggestionType.SUBSCRIPTION_TOKEN,
                    relevanceScore = 0.4,
                    urgency = SuggestionUrgency.LOW,
                    metadata = mapOf("token" to token.symbol, "mint" to token.mint)
                ))
            }
        
        return suggestions
    }
    
    /**
     * Record user action for pattern learning
     */
    fun recordAction(intent: TransactionIntent, timestamp: Long = System.currentTimeMillis()) {
        userPatterns.recordIntent(intent, timestamp)
        
        when (intent) {
            is TransactionIntent.Swap -> {
                if (intent.useJito) {
                    mevAnalyzer.recordJitoUsage(intent.jitoTip ?: 0)
                }
            }
            else -> {}
        }
        
        timePatterns.recordActivity(timestamp)
    }
    
    private fun formatVolume(volume: BigDecimal): String {
        return when {
            volume >= BigDecimal(1_000_000) -> "${volume.divide(BigDecimal(1_000_000)).setScale(1, java.math.RoundingMode.HALF_UP)}M"
            volume >= BigDecimal(1_000) -> "${volume.divide(BigDecimal(1_000)).setScale(1, java.math.RoundingMode.HALF_UP)}K"
            else -> volume.setScale(0, java.math.RoundingMode.HALF_UP).toString()
        }
    }
}

// === Suggestion Types ===

data class SmartSuggestion(
    val id: String,
    val text: String,
    val intent: String?,
    val type: SuggestionType,
    val relevanceScore: Double,
    val urgency: SuggestionUrgency,
    val metadata: Map<String, String> = emptyMap()
)

enum class SuggestionType {
    // MEV
    MEV_TIP_OPTIMIZATION,
    MEV_TIMING,
    MEV_PROTECTION,
    MEV_FASTLANE,
    
    // Portfolio
    REBALANCE,
    STAKING_OPPORTUNITY,
    LOW_BALANCE_WARNING,
    DUST_CONSOLIDATION,
    TAKE_PROFIT,
    
    // Trending
    PUMPFUN_TRENDING,
    TRENDING_SWAP,
    TOKEN_GRADUATION,
    
    // Patterns
    FREQUENT_RECIPIENT,
    FREQUENT_TOKEN,
    RECURRING_PATTERN,
    
    // Time
    ACTIVITY_TIME,
    DAY_PATTERN,
    LOW_FEE_TIME,
    
    // Subscriptions
    SUBSCRIPTION_WALLET,
    SUBSCRIPTION_TOKEN
}

enum class SuggestionUrgency { LOW, MEDIUM, HIGH, CRITICAL }

enum class SuggestionCategory {
    MEV_OPTIMIZATION,
    PORTFOLIO,
    TRENDING,
    PATTERN,
    TIME_BASED,
    SUBSCRIPTIONS
}

data class SuggestionBatch(
    val category: SuggestionCategory,
    val suggestions: List<SmartSuggestion>
)

// === Configuration ===

data class SuggestionConfig(
    val maxSuggestions: Int = 15,
    val rebalanceThreshold: Double = 0.15,
    val stakingSuggestionThreshold: BigDecimal = BigDecimal("5"),
    val lowBalanceThreshold: BigDecimal = BigDecimal("0.1"),
    val dustThreshold: BigDecimal = BigDecimal("0.01"),
    val takeProfitThreshold: Double = 0.5,
    val defaultTrendAmount: BigDecimal = BigDecimal("0.1")
)

// === Provider Interfaces ===

interface PortfolioProvider {
    suspend fun getPortfolio(): Portfolio
}

interface TrendProvider {
    suspend fun getPumpfunTrending(): List<TrendingToken>
    suspend fun getHotSwapPairs(): List<SwapPair>
    suspend fun getGraduatingTokens(): List<GraduatingToken>
}

interface MevDataProvider {
    suspend fun getJitoTipFloor(): Long
    suspend fun getNetworkCongestion(): Double
    suspend fun getMevActivityLevel(): Double
}

// === Data Classes ===

data class Portfolio(
    val solBalance: BigDecimal,
    val stakedBalance: BigDecimal,
    val tokens: List<TokenBalance>,
    val targetAllocations: Map<String, Double> = emptyMap()
) {
    fun calculateImbalance(): Double {
        if (targetAllocations.isEmpty()) return 0.0
        // Simplified imbalance calculation
        return 0.1
    }
    
    fun suggestRebalance(): String {
        return "swap excess tokens to SOL"
    }
    
    fun getUnstakedSol(): BigDecimal = solBalance - stakedBalance
    
    fun getDustTokens(threshold: BigDecimal): List<TokenBalance> =
        tokens.filter { it.usdValue < threshold }
}

data class TokenBalance(
    val symbol: String,
    val mint: String,
    val balance: BigDecimal,
    val usdValue: BigDecimal,
    val unrealizedPnlPercent: Double = 0.0
)

data class TrendingToken(
    val name: String,
    val symbol: String,
    val mint: String,
    val priceChange24h: Double,
    val volume24h: BigDecimal
)

data class SwapPair(
    val inputToken: String,
    val outputToken: String,
    val volume24h: BigDecimal
)

data class GraduatingToken(
    val symbol: String,
    val address: String,
    val progress: Int
)

// === Pattern Analyzers ===

class UserPatternAnalyzer {
    private val recipients = ConcurrentHashMap<String, Int>()
    private val tokens = ConcurrentHashMap<String, Int>()
    private val patterns = mutableListOf<RecurringPattern>()
    
    fun recordIntent(intent: TransactionIntent, timestamp: Long) {
        when (intent) {
            is TransactionIntent.TransferSol -> {
                recipients.compute(intent.recipient) { _, v -> (v ?: 0) + 1 }
            }
            is TransactionIntent.TransferToken -> {
                recipients.compute(intent.recipient) { _, v -> (v ?: 0) + 1 }
                tokens.compute(intent.token) { _, v -> (v ?: 0) + 1 }
            }
            is TransactionIntent.Swap -> {
                tokens.compute(intent.inputToken) { _, v -> (v ?: 0) + 1 }
                tokens.compute(intent.outputToken) { _, v -> (v ?: 0) + 1 }
            }
            else -> {}
        }
    }
    
    fun getFrequentRecipients(): List<Pair<String, Int>> =
        recipients.entries.sortedByDescending { it.value }.map { it.key to it.value }
    
    fun getFrequentTokens(): List<Pair<String, Int>> =
        tokens.entries.sortedByDescending { it.value }.map { it.key to it.value }
    
    fun getRecurringPatterns(): List<RecurringPattern> = patterns.toList()
}

class MevPatternAnalyzer {
    private val jitoTips = mutableListOf<Long>()
    private var fastlaneUsed = false
    private var highPriorityCount = 0
    
    fun recordJitoUsage(tip: Long) {
        jitoTips.add(tip)
    }
    
    fun getAverageUserTip(): Long? =
        if (jitoTips.isEmpty()) null else jitoTips.average().toLong()
    
    fun usesFastlane(): Boolean = fastlaneUsed
    
    fun hasHighPriorityNeeds(): Boolean = highPriorityCount > 5
}

class TimePatternAnalyzer {
    private val hourlyActivity = ConcurrentHashMap<Int, Int>()
    private val dailyActivity = ConcurrentHashMap<DayOfWeek, Int>()
    
    fun recordActivity(timestamp: Long) {
        val dt = LocalDateTime.ofInstant(
            java.time.Instant.ofEpochMilli(timestamp),
            java.time.ZoneId.systemDefault()
        )
        hourlyActivity.compute(dt.hour) { _, v -> (v ?: 0) + 1 }
        dailyActivity.compute(dt.dayOfWeek) { _, v -> (v ?: 0) + 1 }
    }
    
    fun getPeakActivityHours(): Set<Int> {
        if (hourlyActivity.isEmpty()) return emptySet()
        val avg = hourlyActivity.values.average()
        return hourlyActivity.entries
            .filter { it.value > avg * 1.5 }
            .map { it.key }
            .toSet()
    }
    
    fun getDayPatterns(day: DayOfWeek): List<RecurringPattern> = emptyList()
}

data class RecurringPattern(
    val id: String,
    val description: String,
    val intent: String,
    val frequency: String
)
