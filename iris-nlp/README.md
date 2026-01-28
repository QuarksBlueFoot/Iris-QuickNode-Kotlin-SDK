# Iris NLP - Natural Language Transaction Builder

<p align="center">
  <img src="https://i.imgur.com/s8c2LdY.png" alt="Iris SDK" width="200"/>
</p>

> 🗣️ Build Solana transactions by typing in plain English

Iris NLP is a **deterministic, offline-capable** natural language parser for the [Iris SDK](../README.md). It converts human-readable commands into structured transaction intents—no AI, no API calls, instant response.

## Features

- **🚫 No AI/LLM** - Pure regex pattern matching, runs offline
- **⚡ Instant** - Sub-millisecond parsing
- **🔗 Domain Support** - `.sol` (Bonfida SNS) and `.skr` (SKR) domains
- **🪙 Token Resolution** - Jupiter token list + well-known tokens (including memecoins!)
- **💸 Transfers** - SOL and SPL token transfers
- **🔄 Swaps** - Metis-powered token swaps with JITO protection
- **🎰 PumpFun** - Buy/sell/create PumpFun tokens
- **🚀 JITO** - MEV protection and bundle support
- **📡 Yellowstone** - gRPC subscriptions via natural language
- **⚡ Fastlane** - Transaction priority via Fastlane
- **🥩 Staking** - Multi-protocol staking support
- **🔒 Privacy** - Privacy analysis integration

## Installation

```kotlin
// build.gradle.kts
dependencies {
    implementation("xyz.selenus:iris-nlp:1.1.0")
}
```

## Quick Start

```kotlin
import xyz.selenus.iris.nlp.NaturalLanguageBuilder
import xyz.selenus.iris.nlp.DefaultIrisEntityResolver
import xyz.selenus.iris.nlp.ParseResult

// Create the resolver (handles domain/token lookups)
val resolver = DefaultIrisEntityResolver()

// Create the NLP builder
val nlp = NaturalLanguageBuilder.create(resolver)

// Parse natural language
val result = nlp.parse("swap 100 USDC for SOL with jito protection")

when (result) {
    is ParseResult.Success -> {
        val intent = result.intent
        println("Intent: $intent")
        println("Confidence: ${result.confidence}")
    }
    is ParseResult.NeedsInfo -> {
        println("Missing: ${result.missing}")
        println("Suggestion: ${result.suggestion}")
    }
    is ParseResult.Unknown -> {
        println("Try these commands:")
        result.suggestions.forEach { println("  ${it.template}") }
    }
}
```

## Supported Commands

### Transfers

| Pattern | Example |
|---------|---------|
| `send {amount} SOL to {address}` | `send 1 SOL to alice.sol` |
| `transfer {amount} {token} to {address}` | `transfer 100 USDC to moonmanquark.skr` |
| `pay {address} {amount} SOL` | `pay bob.sol 0.5 SOL` |

### Swaps (Metis + JITO)

| Pattern | Example |
|---------|---------|
| `swap {amount} {token} for {token}` | `swap 100 USDC for SOL` |
| `swap {amount} {token} for {token} with jito` | `swap 1 SOL for BONK with jito` |
| `jito protected swap {amount} {token} for {token}` | `jito protected swap 50 USDC for SOL` |
| `buy {amount} {token} with {token}` | `buy 1000 BONK with SOL` |
| `sell {amount} {token} for {token}` | `sell 50 USDC for SOL` |

### PumpFun Trading

| Pattern | Example |
|---------|---------|
| `buy pumpfun {mint}` | `buy pumpfun 9BB6NFk...` |
| `buy pumpfun {mint} with {amount} SOL` | `buy pumpfun 9BB6NFk... with 0.1 SOL` |
| `ape {amount} SOL of {mint}` | `ape 0.5 SOL of pump token` |
| `sell pumpfun {mint}` | `sell pumpfun 9BB6NFk...` |
| `sell {amount} of {mint}` | `sell 50% of pumpfun token` |
| `create pumpfun token {name} symbol {symbol}` | `create pumpfun token "Moon Cat" symbol MCAT` |

### JITO Features

| Pattern | Example |
|---------|---------|
| `get jito tip` | `get jito tip floor` |
| `check jito tip` | `what's the jito tip` |

### Staking

| Pattern | Example |
|---------|---------|
| `stake {amount} SOL` | `stake 10 SOL` |
| `stake {amount} SOL with {protocol}` | `stake 5 SOL with jito` |
| `unstake {amount} SOL` | `unstake 2 SOL` |
| `claim rewards` | `claim my staking rewards` |

Supported staking protocols:
- **Marinade** (mSOL)
- **Jito** (jitoSOL)
- **Blaze** (bSOL)
- **Lido** (stSOL)
- **Socean** (scnSOL)

### Balance & Assets

| Pattern | Example |
|---------|---------|
| `check balance` | `check my balance` |
| `check balance of {address}` | `check balance of alice.sol` |
| `get {token} balance` | `get my USDC balance` |
| `show my NFTs` | `show my NFTs` |
| `list assets of {address}` | `list assets of moonmanquark.skr` |

### Domain Resolution

| Pattern | Example |
|---------|---------|
| `resolve {domain}` | `resolve alice.sol` |
| `what is {domain}` | `what is moonmanquark.skr` |
| `reverse lookup {address}` | `reverse lookup F42Zov...` |
| `get domains of {address}` | `get domains of alice.sol` |

### Yellowstone Subscriptions

| Pattern | Example |
|---------|---------|
| `subscribe {address}` | `subscribe alice.sol` |
| `monitor {address}` | `monitor moonmanquark.skr` |
| `watch {address}` | `watch token account` |
| `subscribe to slots` | `subscribe to slots` |

### Privacy

| Pattern | Example |
|---------|---------|
| `analyze privacy` | `analyze my privacy` |
| `check privacy of {address}` | `check privacy of alice.sol` |

## Configuration

```kotlin
val nlp = NaturalLanguageBuilder.create(resolver) {
    defaultSlippageBps = 50        // Default slippage 0.5%
    pumpfunSlippageBps = 100       // PumpFun slippage 1%
    defaultWallet = "myWallet.sol" // Default wallet for "check my balance"
    useJitoByDefault = true        // Enable JITO for all swaps
}
```

## Domain Support

Iris NLP supports multiple domain systems:

| Domain | Provider | Example |
|--------|----------|---------|
| `.sol` | Bonfida SNS | `alice.sol` |
| `.skr` | SKR Domains | `moonmanquark.skr` |

## Well-Known Tokens

The resolver includes an extensive token cache:

**DeFi & Infrastructure:**
- SOL, USDC, USDT, RAY, ORCA, JUP, PYTH, JTO

**Memecoins:**
- BONK, WIF, POPCAT, MOODENG
- FARTCOIN, AI16Z, GOAT, PNUT

**DePIN:**
- RENDER, HNT, MOBILE, IOT

Unknown tokens can be specified by mint address.

## Parse Results

```kotlin
sealed class ParseResult {
    // Successfully parsed, high confidence
    data class Success(
        val intent: TransactionIntent,
        val confidence: Double,
        val rawInput: String
    )
    
    // Multiple interpretations possible
    data class Ambiguous(
        val possibleIntents: List<TransactionIntent>,
        val clarificationPrompt: String
    )
    
    // Partially understood, needs more info
    data class NeedsInfo(
        val intentType: IntentType,
        val missing: List<String>,
        val partial: Map<String, String>,
        val suggestion: String
    )
    
    // Could not understand
    data class Unknown(
        val input: String,
        val suggestions: List<CommandSuggestion>
    )
}
```

## Intent Types (Extended for QuickNode)

| Intent | Description |
|--------|-------------|
| `TransferSol` | Transfer SOL to an address |
| `TransferToken` | Transfer SPL tokens |
| `Swap` | Swap tokens via Metis (with optional JITO) |
| `SwapExactOut` | Swap with exact output amount |
| `PumpfunBuy` | Buy a PumpFun token |
| `PumpfunSell` | Sell a PumpFun token |
| `PumpfunCreate` | Launch a new PumpFun token |
| `JitoTipIntent` | Get JITO tip floor |
| `Stake` | Stake SOL with a protocol |
| `Unstake` | Unstake SOL |
| `ClaimRewards` | Claim staking rewards |
| `GetBalance` | Query SOL balance |
| `GetTokenBalance` | Query token balance |
| `GetAssets` | Get DAS assets |
| `ResolveDomain` | Resolve .sol/.skr domain |
| `ReverseLookup` | Get domain from address |
| `GetDomains` | Get all domains for an address |
| `AnalyzePrivacy` | Run privacy analysis |
| `SubscribeAccount` | Subscribe to account changes |
| `SubscribeSlot` | Subscribe to slot updates |
| `NftTransfer` | Transfer NFT |
| `NftList` | List NFT for sale |

## Amount Parsing

Supports various number formats:

| Format | Parsed Value |
|--------|-------------|
| `1` | 1 |
| `1.5` | 1.5 |
| `1,000` | 1000 |
| `1k` | 1,000 |
| `1m` | 1,000,000 |
| `1b` | 1,000,000,000 |

## Example: Trading Bot

```kotlin
class SolanaTradingBot {
    private val resolver = DefaultIrisEntityResolver()
    private val nlp = NaturalLanguageBuilder.create(resolver) {
        useJitoByDefault = true
        pumpfunSlippageBps = 200 // 2% for volatile tokens
    }
    
    suspend fun executeCommand(command: String): Result<String> {
        val result = nlp.parse(command)
        
        return when (result) {
            is ParseResult.Success -> {
                when (val intent = result.intent) {
                    is TransactionIntent.PumpfunBuy -> {
                        executePumpfunBuy(intent.tokenMint, intent.solAmount)
                    }
                    is TransactionIntent.Swap -> {
                        if (intent.useJito) {
                            executeJitoProtectedSwap(intent)
                        } else {
                            executeSwap(intent)
                        }
                    }
                    else -> executeGenericIntent(intent)
                }
            }
            is ParseResult.NeedsInfo -> Result.failure(
                Exception(result.suggestion)
            )
            is ParseResult.Unknown -> Result.failure(
                Exception("Unknown command. Suggestions: ${result.suggestions}")
            )
        }
    }
}
```

## Architecture

```
┌────────────────────────────────────────────────────────┐
│                    User Input                           │
│         "buy pumpfun token with 0.1 SOL"               │
└────────────────────────────────────────────────────────┘
                          │
                          ▼
┌────────────────────────────────────────────────────────┐
│                  NaturalLanguageBuilder                 │
│                                                         │
│  ┌─────────────────┐  ┌──────────────────────────────┐ │
│  │ Pattern Matcher │  │     Entity Resolver          │ │
│  │ (Regex-based)   │──│  • SNS (.sol) API           │ │
│  │                 │  │  • SKR (.skr) API           │ │
│  │ • Transfers     │  │  • Jupiter token list        │ │
│  │ • Swaps + JITO  │  │  • Memecoins cache          │ │
│  │ • PumpFun       │  │  • Staking protocols        │ │
│  │ • Yellowstone   │  └──────────────────────────────┘ │
│  │ • Staking       │                                   │
│  └─────────────────┘                                   │
└────────────────────────────────────────────────────────┘
                          │
                          ▼
┌────────────────────────────────────────────────────────┐
│                    ParseResult                          │
│                                                         │
│  Success ─► TransactionIntent (ready to execute)       │
│  NeedsInfo ─► Missing fields + suggestion              │
│  Unknown ─► Command suggestions                        │
└────────────────────────────────────────────────────────┘
```

## Related Modules

- [iris-core](../iris-core) - Core primitives
- [iris-rpc](../iris-rpc) - RPC operations
- [iris-metis](../iris-metis) - Metis DEX aggregator
- [iris-jito](../iris-jito) - JITO MEV protection
- [iris-pumpfun](../iris-pumpfun) - PumpFun integration
- [iris-yellowstone](../iris-yellowstone) - gRPC subscriptions
- [iris-das](../iris-das) - Digital Asset Standard
- [iris-privacy](../iris-privacy) - Privacy analysis

---

<p align="center">
  Built with 💜 by <a href="https://x.com/moonmanquark">@moonmanquark</a> & Selenus
</p>

<p align="center">
  <sub>
    <strong>Donations:</strong> solanadevdao.sol • F42ZovBoRJZU4av5MiESVwJWnEx8ZQVFkc1RM29zMxNT
  </sub>
</p>
