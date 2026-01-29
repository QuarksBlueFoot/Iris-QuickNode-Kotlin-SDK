package xyz.selenus.iris.nlp.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import xyz.selenus.iris.nlp.NaturalLanguageBuilder
import xyz.selenus.iris.nlp.ParseResult
import xyz.selenus.iris.nlp.TransactionIntent
import java.math.BigDecimal
import java.util.Locale

/**
 * Iris Voice Input Integration - QuickNode Enhanced
 * 
 * Voice-first transaction interface with:
 * - Speech-to-text processing
 * - Wake word detection ("Hey Iris", "Hey Solana")
 * - Multi-language support
 * - Voice confirmation flow
 * - Phonetic number parsing
 * - MEV-aware voice commands
 * - PumpFun-specific voice patterns
 * - Accessibility features
 * 
 * Solana Mobile Standard compliant with KMM 2026 architecture
 */
class IrisVoiceInputController(
    private val nlpBuilder: NaturalLanguageBuilder,
    private val speechRecognizer: SpeechRecognizer,
    private val textToSpeech: TextToSpeech,
    private val config: VoiceConfig = VoiceConfig()
) {
    
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()
    
    private val _lastTranscript = MutableStateFlow<String?>(null)
    val lastTranscript: StateFlow<String?> = _lastTranscript.asStateFlow()
    
    private val phoneticParser = PhoneticNumberParser()
    private val mevVoiceParser = MevVoiceParser()
    
    private var pendingIntent: TransactionIntent? = null
    private var confirmationCallback: ((Boolean) -> Unit)? = null
    
    companion object {
        private val WAKE_WORDS = listOf(
            "hey iris",
            "okay iris",
            "hey solana",
            "luna",
            "hi iris"
        )
        
        private val CONFIRMATION_YES = listOf(
            "yes", "yeah", "yep", "confirm", "do it", "send it",
            "go ahead", "execute", "approved", "affirmative"
        )
        
        private val CONFIRMATION_NO = listOf(
            "no", "nope", "cancel", "stop", "abort", "nevermind",
            "wait", "hold on", "don't"
        )
        
        private val MODIFICATION_PATTERNS = listOf(
            Regex("(?:make it|change to|set to)\\s+(.+)", RegexOption.IGNORE_CASE),
            Regex("(?:actually|wait)\\s+(.+)", RegexOption.IGNORE_CASE)
        )
        
        private val MEV_VOICE_PATTERNS = listOf(
            Regex("(?:with|use)\\s+(?:jito|mev protection)", RegexOption.IGNORE_CASE),
            Regex("(?:bundle|protect)\\s+(?:this|the)\\s+(?:swap|transaction)", RegexOption.IGNORE_CASE),
            Regex("(?:set|use)\\s+(?:tip|priority)\\s+(?:to\\s+)?(\\d+)", RegexOption.IGNORE_CASE)
        )
        
        private val PUMPFUN_VOICE_PATTERNS = listOf(
            Regex("(?:ape|buy|snipe)\\s+(?:into\\s+)?(.+?)\\s+(?:on\\s+)?pump\\s*fun", RegexOption.IGNORE_CASE),
            Regex("(?:sell|dump)\\s+(?:my\\s+)?(.+?)\\s+(?:on\\s+)?pump\\s*fun", RegexOption.IGNORE_CASE)
        )
    }
    
    /**
     * Start voice listening with wake word detection
     */
    fun startListening(): Flow<VoiceUpdate> = flow {
        _state.value = VoiceState.WaitingForWakeWord
        emit(VoiceUpdate.Listening)
        
        speechRecognizer.startContinuousRecognition().collect { result ->
            when (result) {
                is SpeechResult.Partial -> {
                    emit(VoiceUpdate.PartialTranscript(result.text))
                    
                    // Check for wake word
                    if (_state.value is VoiceState.WaitingForWakeWord) {
                        if (containsWakeWord(result.text)) {
                            _state.value = VoiceState.WakeWordDetected
                            emit(VoiceUpdate.WakeWordDetected)
                            
                            if (config.playAcknowledgment) {
                                textToSpeech.speak("Listening")
                            }
                        }
                    }
                }
                
                is SpeechResult.Final -> {
                    val transcript = result.text
                    _lastTranscript.value = transcript
                    
                    when (_state.value) {
                        is VoiceState.WakeWordDetected -> {
                            // Process as command
                            val command = removeWakeWord(transcript)
                            emit(VoiceUpdate.Transcript(command))
                            
                            val parseResult = processVoiceCommand(command)
                            emit(VoiceUpdate.ParseResult(parseResult))
                            
                            when (parseResult) {
                                is ParseResult.Success -> {
                                    pendingIntent = parseResult.intent
                                    _state.value = VoiceState.AwaitingConfirmation(parseResult.intent)
                                    
                                    // Request voice confirmation
                                    val summary = parseResult.intent.summary()
                                    val mevInfo = mevVoiceParser.getMevSummary(parseResult.intent)
                                    val fullSummary = if (mevInfo.isNotEmpty()) "$summary. $mevInfo" else summary
                                    
                                    if (config.speakConfirmation) {
                                        textToSpeech.speak("$fullSummary. Say yes to confirm or no to cancel.")
                                    }
                                    
                                    emit(VoiceUpdate.AwaitingConfirmation(fullSummary))
                                }
                                is ParseResult.Failure -> {
                                    if (config.speakErrors) {
                                        textToSpeech.speak("Sorry, ${parseResult.reason}")
                                    }
                                    emit(VoiceUpdate.Error(parseResult.reason))
                                    _state.value = VoiceState.WaitingForWakeWord
                                }
                                is ParseResult.Ambiguous -> {
                                    if (config.speakErrors) {
                                        textToSpeech.speak("That's ambiguous. Did you mean ${parseResult.candidates.first().intent.summary()}?")
                                    }
                                    emit(VoiceUpdate.Ambiguous(parseResult.candidates))
                                    _state.value = VoiceState.WaitingForWakeWord
                                }
                            }
                        }
                        
                        is VoiceState.AwaitingConfirmation -> {
                            val confirmation = parseConfirmation(transcript)
                            emit(VoiceUpdate.ConfirmationResponse(confirmation))
                            
                            when (confirmation) {
                                ConfirmationResponse.YES -> {
                                    val intent = pendingIntent!!
                                    _state.value = VoiceState.Confirmed(intent)
                                    emit(VoiceUpdate.Confirmed(intent))
                                    
                                    if (config.speakConfirmation) {
                                        textToSpeech.speak("Executing transaction")
                                    }
                                }
                                ConfirmationResponse.NO -> {
                                    _state.value = VoiceState.Cancelled
                                    emit(VoiceUpdate.Cancelled)
                                    
                                    if (config.speakConfirmation) {
                                        textToSpeech.speak("Cancelled")
                                    }
                                }
                                is ConfirmationResponse.MODIFY -> {
                                    // Try to modify the pending intent
                                    val modified = modifyIntent(pendingIntent!!, confirmation.newValue)
                                    if (modified != null) {
                                        pendingIntent = modified
                                        _state.value = VoiceState.AwaitingConfirmation(modified)
                                        
                                        if (config.speakConfirmation) {
                                            textToSpeech.speak("Changed to ${modified.summary()}. Confirm?")
                                        }
                                        emit(VoiceUpdate.Modified(modified))
                                    }
                                }
                                ConfirmationResponse.UNCLEAR -> {
                                    if (config.speakErrors) {
                                        textToSpeech.speak("Please say yes or no")
                                    }
                                    emit(VoiceUpdate.NeedsClarification)
                                }
                            }
                        }
                        
                        else -> {}
                    }
                }
                
                is SpeechResult.Error -> {
                    emit(VoiceUpdate.Error(result.message))
                }
            }
        }
    }
    
    /**
     * Stop listening
     */
    suspend fun stopListening() {
        speechRecognizer.stopRecognition()
        _state.value = VoiceState.Idle
    }
    
    /**
     * Process voice command with phonetic parsing and MEV awareness
     */
    private suspend fun processVoiceCommand(command: String): ParseResult {
        // Normalize the command
        var normalized = command.lowercase().trim()
        
        // Parse phonetic numbers ("one point five" -> "1.5")
        normalized = phoneticParser.parse(normalized)
        
        // Parse MEV-related voice commands
        normalized = mevVoiceParser.parse(normalized)
        
        // Parse PumpFun voice patterns
        PUMPFUN_VOICE_PATTERNS.forEach { pattern ->
            pattern.find(normalized)?.let { match ->
                val token = match.groupValues[1].trim()
                normalized = when {
                    normalized.contains("sell") || normalized.contains("dump") ->
                        "sell $token on pumpfun"
                    else -> "buy $token on pumpfun"
                }
            }
        }
        
        // Handle common voice recognition errors
        normalized = fixCommonMisrecognitions(normalized)
        
        // Parse with NLP
        return nlpBuilder.parse(normalized)
    }
    
    private fun containsWakeWord(text: String): Boolean {
        val lower = text.lowercase()
        return WAKE_WORDS.any { lower.contains(it) }
    }
    
    private fun removeWakeWord(text: String): String {
        var result = text.lowercase()
        WAKE_WORDS.forEach { wake ->
            result = result.replace(wake, "").trim()
        }
        return result
    }
    
    private fun parseConfirmation(text: String): ConfirmationResponse {
        val lower = text.lowercase().trim()
        
        // Check for modification
        MODIFICATION_PATTERNS.forEach { pattern ->
            pattern.find(lower)?.let { match ->
                return ConfirmationResponse.MODIFY(match.groupValues[1])
            }
        }
        
        // Check for yes
        if (CONFIRMATION_YES.any { lower.contains(it) }) {
            return ConfirmationResponse.YES
        }
        
        // Check for no
        if (CONFIRMATION_NO.any { lower.contains(it) }) {
            return ConfirmationResponse.NO
        }
        
        return ConfirmationResponse.UNCLEAR
    }
    
    private fun modifyIntent(intent: TransactionIntent, newValue: String): TransactionIntent? {
        val parsed = phoneticParser.parse(newValue)
        
        // Try to parse as amount
        parsed.toBigDecimalOrNull()?.let { amount ->
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
        if (parsed.endsWith(".sol") || parsed.length == 44) {
            return when (intent) {
                is TransactionIntent.TransferSol -> intent.copy(recipient = parsed)
                is TransactionIntent.TransferToken -> intent.copy(recipient = parsed)
                else -> null
            }
        }
        
        // Try to parse as MEV setting
        mevVoiceParser.parseTip(parsed)?.let { tip ->
            return when (intent) {
                is TransactionIntent.Swap -> intent.copy(jitoTip = tip)
                else -> null
            }
        }
        
        return null
    }
    
    private fun fixCommonMisrecognitions(text: String): String {
        return text
            // SOL variations
            .replace(Regex("\\bsole\\b", RegexOption.IGNORE_CASE), "SOL")
            .replace(Regex("\\bsoul\\b", RegexOption.IGNORE_CASE), "SOL")
            .replace(Regex("\\bso l\\b", RegexOption.IGNORE_CASE), "SOL")
            // Token names
            .replace(Regex("\\bbonk\\b", RegexOption.IGNORE_CASE), "BONK")
            .replace(Regex("\\busdc\\b", RegexOption.IGNORE_CASE), "USDC")
            .replace(Regex("\\busdt\\b", RegexOption.IGNORE_CASE), "USDT")
            // Domain names
            .replace(Regex("\\bdot sol\\b", RegexOption.IGNORE_CASE), ".sol")
            .replace(Regex("\\bdot backpack\\b", RegexOption.IGNORE_CASE), ".backpack")
            // JITO/MEV
            .replace(Regex("\\bjee toe\\b", RegexOption.IGNORE_CASE), "JITO")
            .replace(Regex("\\bgee toe\\b", RegexOption.IGNORE_CASE), "JITO")
            // PumpFun
            .replace(Regex("\\bpump fun\\b", RegexOption.IGNORE_CASE), "pumpfun")
            .replace(Regex("\\bpumped fun\\b", RegexOption.IGNORE_CASE), "pumpfun")
    }
}

/**
 * MEV-aware voice command parser
 */
class MevVoiceParser {
    
    private val tipPattern = Regex("(?:tip|priority)\\s+(?:of\\s+)?(\\d+)\\s*(?:k|thousand)?", RegexOption.IGNORE_CASE)
    
    fun parse(command: String): String {
        var result = command
        
        // "with JITO" -> add JITO flag
        if (result.contains(Regex("(?:with|use)\\s+jito", RegexOption.IGNORE_CASE))) {
            result = result.replace(Regex("(?:with|use)\\s+jito", RegexOption.IGNORE_CASE), "")
            if (result.contains("swap") && !result.contains("with JITO")) {
                result = "$result with JITO"
            }
        }
        
        // "protect this swap" -> add JITO
        if (result.contains(Regex("protect\\s+(?:this|the)\\s+swap", RegexOption.IGNORE_CASE))) {
            result = result.replace(Regex("protect\\s+(?:this|the)\\s+swap", RegexOption.IGNORE_CASE), "swap")
            result = "$result with JITO"
        }
        
        return result.trim()
    }
    
    fun parseTip(text: String): Long? {
        tipPattern.find(text)?.let { match ->
            val value = match.groupValues[1].toLong()
            return if (text.contains("k", ignoreCase = true) || text.contains("thousand", ignoreCase = true)) {
                value * 1000
            } else {
                value
            }
        }
        return null
    }
    
    fun getMevSummary(intent: TransactionIntent): String {
        return when (intent) {
            is TransactionIntent.Swap -> {
                if (intent.useJito) {
                    val tip = intent.jitoTip ?: 25000
                    "Using JITO protection with ${tip / 1000}k tip"
                } else ""
            }
            is TransactionIntent.PumpfunBuy -> "Using JITO for MEV protection"
            is TransactionIntent.PumpfunSell -> "Using JITO for MEV protection"
            else -> ""
        }
    }
}

/**
 * Phonetic number parser for voice commands
 */
class PhoneticNumberParser {
    
    private val numberWords = mapOf(
        "zero" to 0, "oh" to 0,
        "one" to 1, "won" to 1,
        "two" to 2, "to" to 2, "too" to 2,
        "three" to 3,
        "four" to 4, "for" to 4,
        "five" to 5,
        "six" to 6,
        "seven" to 7,
        "eight" to 8, "ate" to 8,
        "nine" to 9,
        "ten" to 10,
        "eleven" to 11,
        "twelve" to 12,
        "thirteen" to 13,
        "fourteen" to 14,
        "fifteen" to 15,
        "sixteen" to 16,
        "seventeen" to 17,
        "eighteen" to 18,
        "nineteen" to 19,
        "twenty" to 20,
        "thirty" to 30,
        "forty" to 40,
        "fifty" to 50,
        "sixty" to 60,
        "seventy" to 70,
        "eighty" to 80,
        "ninety" to 90,
        "hundred" to 100,
        "thousand" to 1000,
        "million" to 1000000
    )
    
    /**
     * Parse phonetic numbers in text
     */
    fun parse(text: String): String {
        var result = text
        
        // Handle "X point Y" (e.g., "one point five" -> "1.5")
        result = parseDecimalPhrases(result)
        
        // Handle compound numbers (e.g., "twenty five" -> "25")
        result = parseCompoundNumbers(result)
        
        // Handle simple number words
        result = parseSimpleNumbers(result)
        
        // Handle ordinal numbers
        result = parseOrdinals(result)
        
        // Handle fractions
        result = parseFractions(result)
        
        return result
    }
    
    private fun parseDecimalPhrases(text: String): String {
        val pattern = Regex(
            "(\\w+)\\s+(?:point|dot)\\s+(\\w+(?:\\s+\\w+)?)",
            RegexOption.IGNORE_CASE
        )
        
        return pattern.replace(text) { match ->
            val wholePart = wordToNumber(match.groupValues[1])
            val decimalPart = parseDecimalDigits(match.groupValues[2])
            
            if (wholePart != null && decimalPart != null) {
                "$wholePart.$decimalPart"
            } else {
                match.value
            }
        }
    }
    
    private fun parseDecimalDigits(text: String): String? {
        val words = text.split("\\s+".toRegex())
        val digits = StringBuilder()
        
        for (word in words) {
            val num = wordToNumber(word.lowercase())
            if (num != null && num in 0..9) {
                digits.append(num)
            } else {
                return null
            }
        }
        
        return if (digits.isNotEmpty()) digits.toString() else null
    }
    
    private fun parseCompoundNumbers(text: String): String {
        // Handle "twenty five", "thirty seven", etc.
        val pattern = Regex(
            "(twenty|thirty|forty|fifty|sixty|seventy|eighty|ninety)\\s+" +
            "(one|two|three|four|five|six|seven|eight|nine)",
            RegexOption.IGNORE_CASE
        )
        
        return pattern.replace(text) { match ->
            val tens = wordToNumber(match.groupValues[1]) ?: return@replace match.value
            val ones = wordToNumber(match.groupValues[2]) ?: return@replace match.value
            (tens + ones).toString()
        }
    }
    
    private fun parseSimpleNumbers(text: String): String {
        var result = text
        
        // Sort by length descending to match longer words first
        numberWords.entries.sortedByDescending { it.key.length }.forEach { (word, num) ->
            // Only replace when it's a standalone word (not part of another word)
            val pattern = Regex("\\b$word\\b", RegexOption.IGNORE_CASE)
            result = pattern.replace(result, num.toString())
        }
        
        return result
    }
    
    private fun parseOrdinals(text: String): String {
        return text
            .replace(Regex("\\bfirst\\b", RegexOption.IGNORE_CASE), "1st")
            .replace(Regex("\\bsecond\\b", RegexOption.IGNORE_CASE), "2nd")
            .replace(Regex("\\bthird\\b", RegexOption.IGNORE_CASE), "3rd")
    }
    
    private fun parseFractions(text: String): String {
        return text
            .replace(Regex("\\bhalf\\b", RegexOption.IGNORE_CASE), "0.5")
            .replace(Regex("\\bquarter\\b", RegexOption.IGNORE_CASE), "0.25")
            .replace(Regex("\\bthree quarters\\b", RegexOption.IGNORE_CASE), "0.75")
    }
    
    private fun wordToNumber(word: String): Int? = numberWords[word.lowercase()]
}

/**
 * Voice accessibility features
 */
class VoiceAccessibility(
    private val textToSpeech: TextToSpeech,
    private val config: AccessibilityConfig = AccessibilityConfig()
) {
    
    /**
     * Announce transaction preview for visually impaired users
     */
    suspend fun announcePreview(intent: TransactionIntent) {
        val announcement = buildString {
            appendLine("Transaction preview.")
            
            when (intent) {
                is TransactionIntent.TransferSol -> {
                    appendLine("Sending ${intent.amount} SOL")
                    appendLine("To ${pronounceAddress(intent.recipient)}")
                }
                is TransactionIntent.Swap -> {
                    appendLine("Swapping ${intent.amount} ${intent.inputToken}")
                    appendLine("For ${intent.outputToken}")
                    if (intent.useJito) {
                        appendLine("With JITO MEV protection")
                    }
                }
                is TransactionIntent.PumpfunBuy -> {
                    appendLine("Buying ${intent.tokenMint} on PumpFun")
                    appendLine("For ${intent.solAmount} SOL")
                }
                else -> appendLine(intent.summary())
            }
            
            appendLine()
            appendLine("Say confirm to proceed, or cancel to abort.")
        }
        
        textToSpeech.speak(announcement)
    }
    
    /**
     * Announce transaction result
     */
    suspend fun announceResult(signature: String, success: Boolean) {
        if (success) {
            textToSpeech.speak("Transaction confirmed. Signature is ${pronounceSignature(signature)}")
        } else {
            textToSpeech.speak("Transaction failed. Please try again.")
        }
    }
    
    /**
     * Pronounce address for accessibility
     */
    private fun pronounceAddress(address: String): String {
        return if (address.endsWith(".sol")) {
            address.replace(".sol", " dot sol")
        } else {
            // Read first and last 4 characters
            "${address.take(4)}...${address.takeLast(4)}"
        }
    }
    
    private fun pronounceSignature(signature: String): String {
        return "${signature.take(4)}...${signature.takeLast(4)}"
    }
}

// === Voice State ===

sealed class VoiceState {
    data object Idle : VoiceState()
    data object WaitingForWakeWord : VoiceState()
    data object WakeWordDetected : VoiceState()
    data class Processing(val transcript: String) : VoiceState()
    data class AwaitingConfirmation(val intent: TransactionIntent) : VoiceState()
    data class Confirmed(val intent: TransactionIntent) : VoiceState()
    data object Cancelled : VoiceState()
    data class Error(val message: String) : VoiceState()
}

// === Voice Updates ===

sealed class VoiceUpdate {
    data object Listening : VoiceUpdate()
    data object WakeWordDetected : VoiceUpdate()
    data class PartialTranscript(val text: String) : VoiceUpdate()
    data class Transcript(val text: String) : VoiceUpdate()
    data class ParseResult(val result: xyz.selenus.iris.nlp.ParseResult) : VoiceUpdate()
    data class AwaitingConfirmation(val summary: String) : VoiceUpdate()
    data class ConfirmationResponse(val response: xyz.selenus.iris.nlp.voice.ConfirmationResponse) : VoiceUpdate()
    data class Confirmed(val intent: TransactionIntent) : VoiceUpdate()
    data class Modified(val intent: TransactionIntent) : VoiceUpdate()
    data object Cancelled : VoiceUpdate()
    data object NeedsClarification : VoiceUpdate()
    data class Ambiguous(val candidates: List<xyz.selenus.iris.nlp.ParseResult.Success>) : VoiceUpdate()
    data class Error(val message: String) : VoiceUpdate()
}

// === Confirmation Response ===

sealed class ConfirmationResponse {
    data object YES : ConfirmationResponse()
    data object NO : ConfirmationResponse()
    data class MODIFY(val newValue: String) : ConfirmationResponse()
    data object UNCLEAR : ConfirmationResponse()
}

// === Speech Recognition Interface ===

interface SpeechRecognizer {
    suspend fun startRecognition(): SpeechResult
    fun startContinuousRecognition(): Flow<SpeechResult>
    suspend fun stopRecognition()
    fun isAvailable(): Boolean
    fun getSupportedLanguages(): List<VoiceLanguage>
    fun setLanguage(language: VoiceLanguage)
}

sealed class SpeechResult {
    data class Partial(val text: String, val confidence: Float) : SpeechResult()
    data class Final(val text: String, val confidence: Float) : SpeechResult()
    data class Error(val message: String) : SpeechResult()
}

// === Text to Speech Interface ===

interface TextToSpeech {
    suspend fun speak(text: String)
    suspend fun speakWithRate(text: String, rate: Float)
    suspend fun stop()
    fun isSpeaking(): Boolean
    fun setVoice(voice: VoiceProfile)
    fun getSupportedVoices(): List<VoiceProfile>
}

data class VoiceProfile(
    val id: String,
    val name: String,
    val language: VoiceLanguage,
    val gender: VoiceGender
)

enum class VoiceGender { MALE, FEMALE, NEUTRAL }

// === Voice Language ===

enum class VoiceLanguage(val locale: Locale, val displayName: String) {
    ENGLISH_US(Locale.US, "English (US)"),
    ENGLISH_UK(Locale.UK, "English (UK)"),
    SPANISH(Locale("es"), "Spanish"),
    PORTUGUESE(Locale("pt", "BR"), "Portuguese"),
    FRENCH(Locale.FRENCH, "French"),
    GERMAN(Locale.GERMAN, "German"),
    JAPANESE(Locale.JAPANESE, "Japanese"),
    KOREAN(Locale.KOREAN, "Korean"),
    CHINESE(Locale.CHINESE, "Chinese"),
    RUSSIAN(Locale("ru"), "Russian")
}

// === Configuration ===

data class VoiceConfig(
    val language: VoiceLanguage = VoiceLanguage.ENGLISH_US,
    val playAcknowledgment: Boolean = true,
    val speakConfirmation: Boolean = true,
    val speakErrors: Boolean = true,
    val requireWakeWord: Boolean = true,
    val continuousListening: Boolean = false,
    val hapticFeedback: Boolean = true,
    val wakeWordSensitivity: Float = 0.7f
)

data class AccessibilityConfig(
    val verboseMode: Boolean = false,
    val slowSpeech: Boolean = false,
    val repeatConfirmation: Boolean = true,
    val announceBalance: Boolean = true
)
