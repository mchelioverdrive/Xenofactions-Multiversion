# Configuration Reference

Xenofactions uses Forge's `Configuration` system. The main generated file is normally:

```text
config/hfr.cfg
```

Restart the server after changing configuration unless the generated config comment explicitly says otherwise.

## Daily reward wheel

Manage daily rewards with the level-3 [`/xc daily` commands](commands.md#daily-rewards-forge-1710), including held-item capture, weights/counts, milestones, enablement and timezone. Every successful change validates the whole table, flushes and atomically replaces `config/xenofactions-daily-rewards.dat` (compressed NBT, schema version 1), then publishes it and refreshes open wheel GUIs. Validation or save failure retains the previous active and saved definitions. This server-only definition file is separate from player claim history and pending rewards. Do not edit it concurrently with commands.

On the first server start without that managed file, import category `XENOFACTIONS_24_DAILY_REWARDS` from `config/hfr.cfg` after item registration. Preserve IDs, order, weights, item metadata/NBT, counts, milestone references and timezone. Once the managed file exists, old config values and startup defaults never overwrite it. Invalid managed data at startup is retained and disables new spins; it is never replaced by an import. Invalid import data likewise disables new spins. `/xc daily status` remains available with absolute managed/import paths, the failure stage and exception details; it preserves the first loading failure for later diagnosis. `/xc daily reload` retries after correcting data or filesystem access without restarting. A failed reload retains any previous valid table for reads and spins under its existing settings, and blocks edits until a reload succeeds. An import retry rereads the existing Forge configuration and applies only its daily inputs; unrelated runtime settings are not reloaded, and the configuration file is not saved. Existing managed data always takes precedence, including when unreadable. The following keys are **one-time import inputs**, not routine administration settings:

| Key | Default | Meaning |
| --- | --- | --- |
| `serverTimezone` | `UTC` | IANA timezone shared by all daily claims, e.g. `America/New_York`. Reset is the next local calendar midnight, including daylight-saving transitions. No existing general server timezone is defined. |
| `rewards` | Bread (weight 50, count 8), iron ingots (30, 4), gold ingots (15, 2), diamond (5, 1) | 0–16 entries formatted `id|weight|registry:item|metadata|count|itemTagSNBT`. Unique IDs use 1–32 letters, digits, `_` or `-`. Weights are integers 1–1000000; metadata 0–32767; counts 1–4096. Item NBT is a compound of at most 2048 characters. |
| `streakMilestones` | `7|diamond`, `30|gold` | Up to 32 distinct positive streak day numbers, each referencing an entry in `rewards`. Its full item count and NBT are awarded in addition to the wheel reward; its weight is ignored. |

Example import reward: `named_diamond|5|minecraft:diamond|0|2|{display:{Name:"Daily Diamond"}}`. An empty milestone list disables bonuses. Unknown items, malformed NBT, duplicate IDs/days, invalid timezone, invalid numbers or missing references reject imports; live commands reject the proposed change instead. The combined binary wheel previews plus three largest preview entries must fit within 16000 bytes, leaving room for snapshot fields and saved results from previous definitions under Forge's packet limit; oversized tables are rejected. Held-item capture checks the 2048-character NBT representation limit and persists exact binary NBT, including arrays, names and enchantments. Existing valid saved items remain collectible while disabled or empty. Enabling an empty wheel records enablement but leaves spins unavailable until a reward is added. Milestone references must be removed or reassigned before deleting a referenced reward.

Timezone changes apply immediately to the displayed calendar and future claims. Each new acceptance saves an absolute `AvailableAfter` instant at its original timezone's next midnight; moving the calendar forward cannot bypass it. Pre-migration claims without this field use the permanently retained `LegacyTimezone` from import, including offline players and across subsequent edits/restarts. A date at or earlier than a player's last claimed date is also never eligible, so moving the calendar backward may delay eligibility. Definition-management commands do not rewrite stored claim dates, streaks or earned receipts. The explicit debugging reset described below only adds a one-use eligibility override.

Use `/xdaily` (alias `/daily`, available without operator status) to open the wheel. Eligible players retain the translated login reminder and automatically see the wheel once per login after their saved data loads and the client leaves the terrain loading screen. The client sends readiness once per connection; the existing server task queue checks loaded daily state and the existing date/reset eligibility before calling the same server opening path as `/xdaily`. Disabled/unavailable wheels, already claimed daily rewards and unfinished or awaiting-delivery spins do not automatically open it. Earned-spin balances are not part of this check. Manual opening first suppresses the later automatic opening for that login; duplicate callbacks/readiness messages and dimension/respawn transitions do not repeat it. Logout clears this transient guard, so eligibility is checked afresh at the next login. Closing without spinning consumes no eligibility and `/xdaily` remains usable. No new persistence fields or configuration settings are added, and `/xc daily` administration retains level-3 authorization. Updated client and server implementations must match for the new readiness intent.

Opening the menu does not accept or deliver a new reward. The server selects by integer weight and durably saves the result, exact item escrow and completion deadline before the 4.2-second animation begins. Segments have equal visual area; probability is the entry's weight divided by the sum of all weights. While spinning, the new reward and any streak bonus stay outside collectible items and result labels. Completion requires the server deadline and, while the wheel is open, acknowledgement that the stopped wheel was drawn. The server then reveals the result and waits for its display acknowledgement before automatic delivery. Client acknowledgements cannot bypass the deadline. The GUI closes 1.5 seconds after first displaying that claim's final result; unrelated snapshots do not restart either timer, manual closure cancels its local timer, and another screen is never closed by it.

Closing the GUI preserves the accepted claim and deadline; the server completes and attempts delivery once due without requiring animation callbacks. Disconnecting leaves escrow saved without an early grant. Login after reconnect or restart schedules the same saved claim, without rerolling: before the deadline, reopening animates only the remaining interval; after the deadline, an open wheel settles and reveals the saved result. Without an open wheel, overdue rewards complete and attempt delivery on a server tick. A completed claim awaiting its first delivery also resumes after login. Ordinary completed history does not replay an animation or start a closure timer merely because the GUI is reopened.

A successful claim advances the streak only if the previous claim was on the immediately preceding server calendar date. After a missed date the next claim starts at one; the GUI shows an expired current streak as zero. Opening and closing do not alter stored streaks. Each configured day-number milestone is awarded once per UUID over its lifetime, even after a streak resets; changing its referenced reward does not regrant an already-earned milestone. The GUI shows the next unearned milestone above the current streak. Acceptance counts as the claim even when inventory delivery remains pending.

Newly accepted wheel rewards grant `configured base count × max(1, claim streak)`, calculated with checked 64-bit arithmetic on the server. Eight configured items grant 8, 16, 24 and 56 on consecutive claims 1, 2, 3 and 7; day 7 also grants any separate configured milestone at its unchanged base count. Missing a day restarts the multiplier at one. Weights, chances, spin count and configured counts do not change, and previous multiplied totals never become the next base. The GUI shows the next claim multiplier, wheel tooltips with base/multiplier/total, and the actual saved result calculation after completion. Large counts retain every digit with grouping and fitted labels.

State is in the overworld's `playerdata/<UUID>.dat`, under `ForgeData.PlayerPersisted.xfDailyRewards` (player schema version 4, reading versions 1–3), together with the inventory. It contains the last accepted calendar epoch-day, streak, milestone receipts, saved wheel/result/bonus, revision and ready `Pending` entries. New results save `baseCount`, `multiplier` and final `amount` as longs. New escrow entries store a count-one item template plus a positive long `Remaining`; delivery visits each inventory slot at most twice per entry and creates only stacks that fit, respecting item and inventory limits. Unfinished claims additionally store `SpinItems` and the absolute `CompleteAt` deadline; completion atomically moves those exact entries to `Pending` and records `DeliveryDue` until the first delivery attempt. That attempt clears the flag with the inventory/remaining-escrow transaction, including full inventories or deferred delivery; subsequent retries use Collect. Legacy unfinished and pending stacks retain their exact saved quantities. Claims without multiplier fields remain fixed amounts, including after upgrading their schema; loading, completion and repeated collection never multiply or recreate grants from previews. Optional `AvailableAfter` guards old claim periods and boolean `ClaimReset` supports debugging resets. The UUID always owns rewards, independently of faction NAME identity mode. Metadata, item NBT and registry names are preserved. Live edits invalidate old session revisions, preserving accepted wheel snapshots/results and both escrow stages; only future acceptances use edited definitions. Acceptance, completion and delivery use flushed temporary player snapshots and atomic replacement of that same native player file; failed writes retain the previous state and restore uncommitted inventory changes. Failed completion/automatic-delivery saves are retried at five-second intervals; completed items remain collectible if a delivery write fails. No separate balance or market file participates in delivery. The integrated-server loading hook restores a newer committed UUID snapshot if `level.dat` supplies an older daily revision. Forge's persisted-player clone path carries unfinished and pending rewards across death and dimension respawn.

`/xc daily reset <player>` persists an eligibility override in the same native UUID snapshot. Keep the original `Day`, `AvailableAfter`, streak, milestone receipts, result, wheel and pending items so existing validation and delivery remain valid. The next accepted spin clears the override and retains the streak value; later ordinary dates advance it normally. Repeated debug spins use that retained streak as their multiplier, with a minimum multiplier of one even for stored zero progress. Reset never recalculates an accepted reward. Reset neither grants items nor clears escrow, and invalidates previous GUI intents after saving. Unknown online names, invalid player data or save failures change no player state. The wheel still must be enabled and populated, and earlier pending items must be collected.

Claims attempt delivery after completion and result display, or after the saved deadline when the GUI is closed; **Collect saved items** retries ready items only and cannot release an unfinished spin. Collection of unrelated ready items remains available during animation. Delivery messages are sent only after inventory items are committed; full inventories, missing registry items and deferred delivery use their existing translated messages. Partial inventory capacity delivers only what fits and keeps every remaining item. Nothing is dropped into the world, including in creative mode. Missing mod items remain pending under their registry names. New claims require completion of an unfinished spin and collection of earlier pending items, including after a debugging reset, bounding retained storage without deleting rewards. Delivery is deferred in TDM worlds, during inventory kit editing, while another container is open, or while holding a cursor stack. There are no cash, prestige, TDM-money, permission or paid-spin rewards. Translations use `daily.hfr.*`, `gui.hfr.daily.*` and `commands.hfr.daily.usage` in the existing language resource. Client and server daily packet versions must match.

Source/build verification cannot establish actual client rendering or persistence under forced process termination. In-game wheel alignment, duplicate requests, full/partial inventory collection, disconnect/reconnect, death, dedicated-server restart, integrated-server recovery, DST boundaries and storage failures require runtime validation. Atomic replacement support is required; unsupported filesystems reject the transaction. Normal Minecraft saves and unrelated gameplay retain their own durability behavior.

## Achievements and earned wheel spins

The Forge Xenofactions achievement page includes 18 goals. The original 17 automatically credit one earned spin per UUID, including already-saved native acquisition achievements. **Guns don't kill people, I do** (`kill1000`) requires 1,000 cumulative player kills, including existing recorded kills, and grants no spin, item, money or other reward. Stable IDs are `achievement.hfr.windmill`, `fishingNet`, `mine500`, `kill1`, `kill20`, `kill50`, `kill100`, `kill150`, `kill200`, `kill500`, `kill1000`, `city`, `statue`, `university`, `warpTent`, `temple`, `officerChest` and `mapInspector` (all with the `achievement.hfr.` prefix). Page descriptions show server progress clamped to its target and completion, plus spin credit only for goals that grant a spin; long titles also appear in the wrapped description. Client and server must use matching implementations.
Mining sums distinct non-null native mining statistics, deduplicating shared block variants. Kills use Minecraft's cumulative player-kill statistic. Map Inspector sums walking (including sprinting), swimming, diving, upward climbing, airborne horizontal movement, minecart, boat, pig and horse statistics; falling is excluded to avoid overlapping distance. Native distances are centimeters (100 per block). Native rounding, airborne minimum distance, signed integer statistic limits and mod harvest/vehicle behavior apply; only actions actually recorded by Minecraft contribute. Saved totals count immediately; clients never supply totals. Checks run after login on the server END tick, on logout, successful XF actions and every 100 server ticks, without chunk discovery or per-tick mining scans. Login synchronization resolves the current online player by UUID and waits for its matching play handler, open channel and Forge dispatcher; logout cancels that pending synchronization. Logout saves progress/reward receipts without native unlock notifications, wheel refreshes or achievement packets to the departing player, including during queued action flushing. A failed handshake that never admitted the player is skipped because its player data may not have loaded.

Set `XENOFACTIONS_25_ACHIEVEMENTS.mapInspectorDistanceBlocks` in `hfr.cfg` (default **10000**, range 1–100000000). This value is read at startup; restart to apply file edits. It has no silence, consecutive-time, territory or dimension requirement. Completion stays earned if the target later changes. City and building progress starts when this implementation is installed: founding must succeed, relocation does not count, and building credit waits for final Forge placement approval. A completed multiblock is one placement. Restoring/loading structures never reconstructs historical action progress.

Player schema **4** reads schemas 1–3 and retains daily dates, streaks, milestone receipts, eligibility overrides and fixed saved item quantities. Under `ForgeData.PlayerPersisted.xfDailyRewards`, `Achievements.Progress` holds the six successful XF action flags as longs 0/1; `Achievements.Completed` and `Achievements.Rewarded` hold separate completion and credit receipts keyed by stable short IDs. `AchievementSpins` is a nonnegative integer balance. Credit and receipt are committed together in the existing atomic UUID player snapshot. `/xdaily`, daily rollover, login and logout never reset achievements, partial native statistics, reward receipts or unclaimed earned spins. Daily redemption/streak state stays separate; the persistent `Rewarded` receipts prevent reconnects and rollover from crediting completed achievements again, even after their spins are spent. No player-data deletion or schema change is required. Failed action saves retain pending observations for retry during this server session; unsaved observations cannot survive process loss. Normal Minecraft statistics and world actions retain their existing independent save behavior.

Open `/xdaily` and use **Earned spin**. It spends one earned spin on the existing enabled item wheel, using its current configured weights and **base quantities at x1**, with no streak or milestone bonus. Daily `Day`, `Streak`, `AvailableAfter`, `ClaimReset` and milestone receipts are preserved. The current wheel's `AchievementSpin` marker distinguishes its source. Spending, the frozen wheel/result, `SpinItems` and `CompleteAt` are persisted in one transaction before animation. Both spin types share the existing 4.2-second animation, local sounds, result display, automatic closure, pending delivery, retry and inventory rollback. Only one accepted result may be unfinished or awaiting first delivery; collect remaining items before another spin. Repeated/stale intents cannot spend twice, and reopening/reconnecting/restarting resumes the saved result without rerolling. No separate wheel, daily quest or paid-spin system is added.

While online, each player has an independent five-minute achievement-spin check, starting at login. The existing daily server tick handler reads the current `AchievementSpins` balance and privately sends `You have unclaimed achievement spin rewards! Run /xdaily to use them.` only when it is positive. Regular daily availability does not trigger this reminder. Timing survives respawns and dimension changes, clears on disconnect/server reset, and restarts on reconnect. Deadlines use elapsed wall-clock time, checked every 20 server ticks; server lag can delay delivery. No additional packets, saved fields or settings are required.

## Chat reward phrases

Forge 1.7.10 loads the server-only UTF-8 file `config/xenofactions-chat-rewards.json` at server start, after item registration. The first start creates `{"version":1,"phrases":[]}`; no phrases or rewards are enabled by default. Direct file edits require a restart. Level-3 `/xc chatreward` commands validate and atomically save changes immediately; do not edit the file concurrently with commands. Definitions are never sent to clients. Ordinary players cannot list or inspect them.

```json
{
  "version": 1,
  "phrases": [
    {
      "id": "welcome_gift",
      "phrase": "Welcome to Xenofactions",
      "enabled": true,
      "repeatable": false,
      "cooldownSeconds": 60,
      "startsAt": "",
      "expiresAt": "",
      "totalLimit": -1,
      "rewards": [
        {"item": "minecraft:bread", "metadata": 0, "count": 8, "nbt": "{}"},
        {"item": "minecraft:diamond", "metadata": 0, "count": 1, "nbt": "{display:{Name:\"Welcome Gift\"}}"}
      ]
    }
  ]
}
```

The stable `id` contains 1–48 lowercase letters, digits, `_` or `-`. Up to 256 phrases are supported. The complete raw chat message matches after trimming its edges and lowercasing with `Locale.ROOT`; internal spaces remain significant. Phrases contain 1–100 legal chat characters and cannot start with `/`. Substrings and slash commands never match. Duplicate text is rejected under those rules, including disabled definitions. `enabled` defaults to false; disabled drafts may have no rewards. Every supplied reward entry is validated, and enabling requires 1–16 valid entries. Unknown items, malformed compound SNBT, invalid numbers/times and duplicate IDs/text reject the entire file at startup, leaving it untouched and disabling phrase matching until repaired and restarted. Command validation failures retain the previous live definitions.

Each reward uses a registered item name, integer metadata 0–32767, integer count 1–4096, and item-tag SNBT of at most 8192 characters. Metadata defaults to 0, count to 1 and NBT to `{}`. Unknown JSON fields, null/incorrect field types, fractional numbers and overflowing integers are rejected. The total expanded reward may contain at most 8192 legal item stacks. `/xc chatreward addheld <id>` copies an administrator's server-owned held stack without consuming it. It stores optional `nbtBase64`: Base64 of an uncompressed binary NBT compound, including its type/name header, with `nbt` left as `{}`. This preserves exact NBT types, byte arrays and explicit empty compounds that legacy `NBTTagCompound.toString()` cannot round-trip. Leave `nbtBase64` empty or omit it when editing SNBT; do not supply both nonempty binary NBT and nonempty SNBT. Both formats must serialize to at most 16384 bytes; binary input also uses a 65536-byte NBT parser allocation quota. Ordinary `{}` SNBT represents an untagged stack. Item registry names are persisted alongside exact stack data so missing mod items remain pending until restored.

By default each UUID can successfully redeem each ID once, independently of faction identity settings. `repeatable: true` permits further redemptions after `cooldownSeconds` (default 60, range 1–315360000). All repeatable phrases enforce at least one second between accepted redemptions. `startsAt` and `expiresAt` default to empty (unbounded); nonempty values must be positive ISO-8601 instants with an offset, such as `2026-12-01T00:00:00Z`, with expiry strictly after start. Start is inclusive and expiry exclusive. `totalLimit` defaults to -1 (unlimited); 0 prevents new redemptions. Limits apply to accepted rewards, including items awaiting collection. Muted players cannot redeem. Earlier pending items for that ID must be collected before another redemption; resending its phrase retries collection without granting a second reward.

The existing UniMixins early loader installs a required, remapped `NetHandlerPlayServer.processChatMessage(C01PacketChatMessage)` HEAD injection. Matched packets are cancelled before vanilla whitespace normalization, command dispatch, `ServerChatEvent`, faction/alliance/public dispatch, chat filtering or `PublicChat` bridge callbacks. Disabled, exhausted, premature, expired and already redeemed phrases are also private. Unmatched packets take the unchanged chat path. Duplicate queued intents for a UUID/ID coalesce; processing rechecks current configuration, connection, mute status, history and limits on the owning server thread through the existing always-registered server task queue. No custom redemption packet accepts client item stacks or counters. Third-party transformations that broadcast before this hook, or manually bypass the vanilla packet handler, are outside this interception boundary.

The overworld owns `data/hfr_chat_rewards.dat` (schema version 1): per-ID total counts, per-UUID counts/last redemption timestamps, exact pending rewards and delivery intents. Acceptance atomically saves the count, cooldown and escrow together before any inventory grant; simultaneous players serialize on the server thread. Inventory delivery first saves an intent while retaining original escrow, then atomically replaces the native `playerdata/<UUID>.dat` with both the inventory and `ForgeData.PlayerPersisted.xfChatRewardDelivery` receipt. It finally reduces escrow according to that receipt. Interrupted finalization is recovered before any later delivery can overwrite the marker; an absent receipt leaves original escrow available. The integrated-server load hook restores a newer committed UUID snapshot if `level.dat` supplied an older delivery revision. The marker follows Forge's persisted-player clone path; eligibility itself stays in the world ledger. Files are flushed before atomic replacement; unsupported atomic moves or corrupt state refuse mutation and retain the previous committed data. Restore world ledger and player snapshots together when restoring backups. Normal Minecraft saves and unrelated gameplay retain their existing durability behavior.

Delivery fills available inventory space and retains every leftover, including in creative mode, without dropping items. It is deferred during TDM/kit editing, while another container is open, or with a cursor-held stack. `/xrewards collect` retries all saved rewards, including from disabled, expired or removed phrases; login reminds players about pending items. Editing spelling/rewards, disabling, removing or recreating an ID never erases history or changes already accepted items. Only `/xc chatreward reset <id> confirm` clears all UUID counts/cooldowns and that ID's total count; it preserves pending items and in-flight receipts. Successful acceptance is logged through `XFLog.info` as `[ChatRewards] Redeemed phrase=<internal ID> player=<UUID> count=... total=...`. Player/admin responses use `chatreward.hfr.*` translations.

Compilation and source tracing cannot prove interruption recovery or mod interoperability in a running server. Dedicated/integrated restarts, forced interruption at delivery stages, full/partial inventories, missing item restoration, simultaneous final-limit claims, repeated packets, cooldown/time boundaries, death, mute interaction and early Mixin conflicts still require runtime feedback.

## Player identity

`XENOFACTIONS_09C_PLAYER_IDENTITY.playerIdentityMode` accepts case-insensitive `AUTO`, `UUID`, or `NAME` and defaults to `AUTO`. `AUTO` selects `NAME` in Forge's deobfuscated development environment or with `online-mode=false`, and `UUID` on a packaged online-mode server. Invalid values fail closed to `UUID`. Startup logs both modes and warns prominently for `NAME`; debug logging does not affect selection.

`UUID` uses the authenticated UUID and is secure production behavior; last-known names never authorize. `NAME` uses the trimmed, `Locale.ROOT`-lowercase `GameProfile` name so test membership survives transient UUID changes, but cannot protect against username reuse or changes. Ambiguous stored names fail closed.


## Main feature toggles

Category: `XENOFACTIONS_01_MODULES`

| Key | Default | Meaning |
| --- | ---: | --- |
| `enableSurvivalRecipes` | `true` | Register the audited survival recipes for faction, prestige, production, support infrastructure, and the fortifications listed in [survival recipes](survival-recipes.md). Set `false` to omit these optional recipes; registrations and unrelated recipes are unchanged. |
| `enableDynmapIntegration` | `true` | Try to publish faction city/claim markers through Dynmap when Dynmap is installed. |
| `enableJourneyMapIntegration` | `true` | Enable optional client overlays for JourneyMap 6.0.x on Minecraft 1.7.10; the 5.2.x hook remains available. |
| `enableTDM` | `true` | Register and initialize the optional team-deathmatch module. |
| `enableCustomFactionFlags` | `true` | Allow `/c flag seturl`, `clear`, and `reload` custom flag workflows. |
| `enableNewPlayerProtection` | `false` | Enable starter protection systems. |
| `enableConquestFlagsCommand` | `true` | Enable conquest flag command/system toggle. |
| `enableGuideBook` | `true` | Enable guide-book integration/fallback references. |
| `enableNEIIntegration` | `true` | Register optional Not Enough Items handlers when NEI is installed; ordinary gameplay recipes and stone-drop behavior are unchanged. |

## TDM compatibility

Category: `XENOFACTIONS_20_TDM`

| Key | Default | Meaning |
| --- | ---: | --- |
| `tdmBombUnknownRemovalAsDefuse` | `true` | Treat a tracked HBM CSGO charge that disappears safely before its brief disarmed state can be observed as a CT defuse, but only with a valid support face, a safely positive timer, and no detonation evidence. Set `false` to preserve strict `OBJECTIVE_ERROR` handling. |
| `tdmBombLifecycleDebug` | `false` | Log detailed reflected HBM lifecycle transitions. Compatibility warnings and objective errors remain visible when disabled. |

## Prestige generation

Category: `XENOFACTIONS_02_PRESTIGE_GENERATION`

| Key | Default | Meaning |
| --- | ---: | --- |
| `startingPrestige` | `250` | Prestige granted to new factions. |
| `basePrestigeGeneration` | `25` | Base prestige generation amount. |
| `prestigeGenerationCap` | `2500` | Cap for generated prestige. |
| `grainmillPrestigeGeneration` | `3` | Grain mill contribution. |
| `universityPrestigeGeneration` | `60` | University contribution. |
| `templePrestigeGeneration` | `90` | Temple contribution. |
| `statuePrestigeGeneration` | `15` | Statue contribution. |
| `cityCenterPrestigeGeneration` | `0` | City center contribution. |

Missing settings use the original building defaults above; existing explicit values remain unchanged. If a configuration was generated with the increased defaults, restore these four keys manually to use the original rates. Existing saved faction generation totals are not recalculated. City center income remains `0`. These are amounts per accounting period; `prestigeDelay` still controls the accounting interval.

## Costs, upkeep, and bankruptcy

Categories: `XENOFACTIONS_03_PRESTIGE_COSTS_UPKEEP` and `XENOFACTIONS_04_BANKRUPTCY`

| Key | Default | Meaning |
| --- | ---: | --- |
| `warpCreationCost` | `125` | Cost to create a faction warp. |
| `warpTentUpkeep` | `75` | Warp tent upkeep. |
| `claimFlagUpkeep` | `1` | Upkeep for claim flags. |
| `settlementCityUpkeep` | `10` | Settlement city upkeep baseline. |
| `warDeclarationBaseCost` | `150` | Base cost to declare war. |
| `warDeclarationTargetPrestigeFactor` | `0.15` | Additional war cost factor based on target prestige. |
| `activeWarUpkeep` | `75` | Ongoing active-war upkeep. |
| `enemyKillPrestigeReward` | `25` | Prestige awarded for killing a member of a faction your faction explicitly marks as an enemy. |
| `warUpkeepHourlyGrowth` | `0.25` | Linear hourly active-war upkeep growth. |
| `warUpkeepHourlyGrowthSquared` | `0.05` | Squared hourly active-war upkeep growth. |
| `surrenderPrestigeTransferPercent` | `0.50` | Prestige transfer percent for surrender tribute. |
| `surrenderTributeDurationHours` | `84` | Surrender tribute duration. |
| `enableNegativePrestigePenalties` | `true` | Enable negative-prestige penalties. |
| `financialCrisisThreshold` | `0` | First penalty threshold. |
| `nationalCollapseThreshold` | `-500` | Second penalty threshold. |
| `fallenNationThreshold` | `-1000` | Third penalty threshold. |
| `financialCrisisUpkeepMultiplier` | `1.25` | Upkeep multiplier at crisis. |
| `nationalCollapseUpkeepMultiplier` | `1.50` | Upkeep multiplier at collapse. |
| `fallenNationUpkeepMultiplier` | `2.00` | Upkeep multiplier at fallen-nation status. |

## Claims and cities

Category: `XENOFACTIONS_05_CLAIMS_CITIES`

| Key | Default | Meaning |
| --- | ---: | --- |
| `maxCityRadius` | `6` | Maximum city radius. |
| `claimTerrainMinArea` | `64` | Deprecated compatibility key; ignored. No minimum land area. |
| `claimTerrainCoveragePercent` | `25` | Deprecated compatibility key; ignored. No minimum land coverage. |
| `claimTerrainMaxStep` | `1` | Deprecated compatibility key; ignored. No connectivity or height-step restriction. |
| `claimTerrainMaxAgeTicks` | `100` | Optional diagnostic-cache age in server ticks; grants read terrain immediately. |
| `claimTerrainRefreshNodes` | `2` | Tracked diagnostic nodes visited every 20 server ticks. |
| `minimumCitySpacingChunks` | `13` | Minimum spacing between city centers. |
| `claimNameMinLength` | `1` | Minimum claim-name length. |
| `claimNameMaxLength` | `32` | Maximum claim-name length. |
| `claimNameRequireUnique` | `true` | Require unique claim names. |
| `claimRenameOfficersAllowed` | `true` | Allow officers to rename claims. |
| `peaceCityTransfersEnabled` | `true` | Allow city transfer terms in peace. |
| `surrenderTransfersCities` | `true` | Allow city transfer behavior on surrender. |
| `cityRadii` | `[2,3,4,5,6]` | City radius per city level. |
| `cityUpgradeCosts` | `[75,150,300,600,1000]` | Upgrade cost per city level. |
| `cityUpkeep` | `[10,25,50,90,140]` | Upkeep per city level. |
| `cityFoundingCostGrowth` | `0.50` | Cost growth for founding additional cities. |
| `cityRelocationEnabled` | `true` | Enable owner-only, two-phase City Center relocation. |
| `cityRelocationFreeDistanceBlocks` | `10` | Free horizontal move distance. |
| `cityRelocationBasePrestigeCost` | `0` | Legacy base-cost key; the default relocation formula has no base cost. |
| `cityRelocationPrestigePerExtraBlock` | `30` | Cost per additional horizontal block, rounded up. |
| `cityRelocationMaxDistanceBlocks` | `256` | Maximum horizontal move distance. |
| `cityRelocationMoveLimit` | `3` | Successful moves allowed per stable city ID in the rolling window. |
| `cityRelocationWindowHours` | `168` | Rolling move-frequency window (seven days). |
| `cityRelocationRepeatCooldownMinutes` | `30` | Delay after the second successful move before the third move. |
| `capitalChangeCooldownHours` | `168` | Hours between leader-only designated-capital changes, independent of city level. Minimum 1 hour. |
| `cityRelocationPendingMinutes` | `30` | Pending transaction lifetime; expiration leaves the old city unchanged. |
| `warpCost` | `125` | Legacy alias still read in this category. |
| `territoryDelay` | `5` | Ticks between territory validation operations. |
| `territoryAmount` | `50` | Chunks checked per territory operation. |
| `prestigeDelay` | `72000` | Ticks between prestige updates; default is one hour. |
| `disableChests` | `true` | Prevent placing chests outside claims. |
| `mold` | `360000` | Ticks before loaded cardboard boxes rot. |
| `freeRaid` | `true` | Ignore raidability checks and make everyone raidable. |

## War and diplomacy

Category: `XENOFACTIONS_06_WAR_DIPLOMACY`

| Key | Default | Meaning |
| --- | ---: | --- |
| `warEnabledDefault` | `false` | Whether war declarations begin enabled on startup. |
| `onlinePlayerThreshold` | `2` | Online-player threshold for war/raid checks. |
| `raidGraceAfterOnlineDropMinutes` | `30` | Grace after online count drops. |
| `surrenderCooldownHours` | `84` | Surrender cooldown. |
| `peaceCooldownHours` | `84` | Peace cooldown. |
| `ceasefireCooldownHours` | `24` | Ceasefire cooldown. |
| `allianceBreakCooldownHours` | `24` | Cooldown after breaking alliances. |
| `alliesCanJoinWars` | `true` | Allow allies to join wars. |
| `alliesCanDeclareWarOnEachOther` | `false` | Allow allies to declare war against each other. |

## New-player and build protection

Category: `XENOFACTIONS_07_NEW_PLAYER_PROTECTION`

| Key | Default | Meaning |
| --- | ---: | --- |
| `pvpGraceDurationHours` | `4` | PvP grace duration. |
| `keepInventoryDurationHours` | `24` | Keep-inventory grace duration. |
| `graceBuildEnabled` | `true` | Enable faction build grace. |
| `graceBuildOneTimeUse` | `true` | Restrict build grace to one use. |
| `graceBuildDurationHours` | `48` | Build grace duration. |

## Custom flags

Category: `XENOFACTIONS_08_CUSTOM_FLAGS`

| Key | Default | Meaning |
| --- | ---: | --- |
| `allowedImageHosts` | `postimages.org`, `postimg.cc`, `i.postimg.cc` | Exact hosts allowed for imported faction flag pages, images and every redirect. |
| `maxImageWidth` | `1024` | Maximum image width. |
| `maxImageHeight` | `1024` | Maximum image height. |
| `maxFileSizeBytes` | `1048576` | Maximum downloaded image and sanitized PNG size. |
| `downloadTimeoutMs` | `5000` | Shared download time budget for DNS waits, page/image requests and redirects. |
| `maxRedirects` | `3` | Maximum HTTPS redirects. |
| `importRateLimitSeconds` | `60` | Faction-wide cooldown after the server successfully installs an imported image. Failed imports do not start or reset it. |
| `reloadMissingFileClearsMetadata` | `true` | Clear stale metadata if reload cannot find cached image. |

`/c flag seturl` accepts HTTPS direct PNG/JPEG links (normally
`https://i.postimg.cc/.../image.png`) and individual Postimages image pages on
`https://postimg.cc/...` or `https://postimages.org/...` that expose an
`og:image:secure_url`, `og:image`, or `img#main-image` CDN link. Pages resolve once
to `i.postimg.cc`; HTML never reaches the image decoder. Galleries, upload/home
pages without image metadata, authentication/challenge pages and other image
formats are unsupported. HTTPS uses the default port or explicit port 443.
Every page, CDN and redirect host must be allowed. Existing configured allowlists
are preserved: add `postimg.cc` explicitly if an older list contains only
`postimages.org` and `i.postimg.cc`.

Page responses are limited to 512 KiB or `maxFileSizeBytes`, whichever is smaller;
images retain the configured byte/dimension limits and are center-cropped into
64×64 PNGs. Redirect limits and the download deadline span page resolution and
the resulting image request. Native DNS operations may outlive cancellation in
the operating system, but the import stops waiting at its deadline and resolver
workers/queues are bounded. Failures log stages, hosts or HTTP status without URL
query strings. Imports of the same exact URL share pending download/decoding work.

The server saves the processed PNG and its hash, **not the source URL**. `reload`
rechecks that saved PNG; it does not fetch a remotely changed image. Run `seturl`
again to import remote changes. Wearable claim banners use this same image and
client texture cache as placed faction flags. Server-owned equipment metadata
updates the wearer and observers when flags or factions change; leaving a faction
clears the banner's faction appearance. Built-in colors, model placement and
movement are unchanged. Custom images use their own colors without a built-in
overlay, with the existing tricolor texture while loading or on failure. Client
cache I/O/decoding runs off-thread; texture registration/replacement/deletion runs
on the client thread, with requests deduplicated by image hash and retried no more
than once per 30 seconds. Disconnect releases textures. Matching client/server
builds are required; no client restart is needed to apply an imported flag.

The import cooldown uses the faction UUID, so renaming the faction or changing the
requesting player does not bypass it. Refused imports show the remaining seconds.
Only one import may be active per faction; this in-progress state is separate from
the cooldown. Invalid URLs, unsupported hosts/images, download failures and save
failures release the active import without adding a rejection cooldown, allowing
an immediate retry unless a previous successful import's cooldown still applies.
Cooldowns and active requests are session-only and clear on server shutdown.

Before installation, the server rechecks the faction and the requesting player's
current flag-change authority. A successful built-in selection, `clear` or `reload`
cancels an older pending import without resetting a success cooldown. Installation
uses atomic replacement in the flag directory; if the filesystem cannot perform
it, the import fails and preserves the previous image and hash. Hash/client
metadata changes only after installation. Server unload discards pending results.

## Wall Art

Category: `XENOFACTIONS_08B_WALL_ART`

Wall Art has an independent source policy; existing custom-flag host settings do not
restrict Wall Art. Hosts are matched exactly after lowercase and trailing-dot
normalization. Every HTML-resolved image and redirect is checked again.

| Key | Default | Meaning |
| --- | ---: | --- |
| `allowedImageHosts` | `postimg.cc`, `i.postimg.cc`, `postimages.org` | Exact HTTPS page and image hosts allowed for Wall Art imports. |
| `maxSourceBytes` | `16777216` | Maximum remote image size; HTML resolution is separately capped at 512 KiB. |
| `maxSourceDimension` | `8192` | Maximum source width or height before decode. |
| `maxSourcePixels` | `67108864` | Maximum source pixel count before decode. |
| `downloadTimeoutMs` | `10000` | Connect and read timeout. |
| `maxRedirects` | `3` | Maximum manually validated HTTPS redirects. |
| `successCooldownSeconds` | `5` | Delay after a successful import. |
| `failureCooldownSeconds` | `1` | Short retry delay after a failed import. |

The importer accepts either a direct PNG/JPEG resource or a bounded HTML image page.
For a page it prefers `og:image:secure_url`, then `og:image`, then the first usable
`img` source. It never executes page scripts, and clients only receive the processed,
server-stored PNG.

## Dynmap

Category: `XENOFACTIONS_09_DYNMAP`

With integration enabled, discovery checks the case-sensitive Forge mod ID `Dynmap`, as registered by GTNH-Web-Map's `@Mod` annotation; jar filenames and `mcmod.info` labels are not used for detection. Absent Dynmap or missing optional Dynmap classes log one `Dynmap unavailable` error with the expected/detected mod ID and a specific reason, then disable marker updates for that server session. Starting another server session resets discovery, diagnostics and cached marker state.

An installed Dynmap whose common API is null, whose marker API is not initialized, or whose marker API/set is unavailable keeps the refresh pending at `updateIntervalTicks`. Discovery, waiting reasons and completed refreshes are logged only when the status changes. Existing markers are not cleared until the API and all required marker sets are available. A refresh is marked complete only after all marker passes finish; rejected marker creation and other reflection/marker failures remain visible as errors. Once the API is ready, saved claims are rebuilt on the server tick without players replacing flags or changing territory. No Dynmap fullrender is needed for these vector overlays. Publication does not depend on Earth datasets, terrain eligibility, zoom or loaded claim chunks.

| Key | Default | Meaning |
| --- | ---: | --- |
| `markerSetId` | `xenofactions_cities` | Dynmap marker set ID. |
| `markerSetLabel` | `Faction Cities` | Dynmap marker set label. |
| `updateIntervalTicks` | `600` | Marker refresh interval. |
| `claimFillOpacity` | `0.18` | Claim area fill opacity. |
| `claimLineOpacity` | `0.0` | Claim area outline opacity. |
| `claimLineWeight` | `0` | Claim outline weight. |
| `borderLineOpacity` | `0.9` | City border line opacity. |
| `borderLineWeight` | `3` | City border line weight. |
| `showCityCenterMarkers` | `true` | Show a point marker at each city center. |
| `showClaimDetailsInLabels` | `true` | Include claim details in Dynmap labels. |
| `showPrestigeDetailsInLabels` | `true` | Include prestige/upkeep details in labels. |
| `showTdmSpawns` | `false` | Publish exact active TDM map spawn positions to the public Dynmap web map. |
| `dynmapDimensionWorldMap` | `0=auto`, `-1=auto`, `1=auto` | Resolve GTNH/Dynmap's actual Forge world IDs, or supply explicit dimension-to-world overrides. |

Dynmap publishes city claims in the configured city set, other faction claims in `xenofactions_claims`, SAFEZONE/WARZONE territories in `xenofactions_zones`, and the enabled active TDM map's bounds and Search and Destroy sites in `xenofactions_tdm`. Sites appear only when the selected pairing is Search and Destroy; optional spawn markers come only from the active mode's spawn set. Other maps and world-border exemptions are not published to the public web map. Each published dimension needs a valid `dynmapDimensionWorldMap` entry. These are separate Xenofactions-owned marker sets; third-party markers are not modified. Marker updates are bounded by `updateIntervalTicks` (default 30 seconds). Territory geometry uses Clowder's saved coordinate bounds, including its legacy negative-coordinate behavior.

Use `0=auto` for the overworld. Automatic resolution calls the installed mod's `ForgeWorld.getWorldName(World)` and `DynmapWorld.normalizeWorldName(String)`, so it follows GTNH's configured naming mode rather than guessing a dimension, provider, or save-folder name. The old stock entries `0=world`, `-1=world_nether`, and `1=world_the_end` also resolve automatically in existing configs; other explicit IDs remain overrides. For the custom overworld `earth_1-4000_xenofactions_1.7.10_earth4000nopop`, an explicit entry is `0=earth_1-4000_xenofactions_1.7.10_earth4000nopop`. Use the world ID from the web configuration, not its display title. Missing or empty dimension entries still suppress publication. Automatic entries wait for that dimension to load; server world load/unload events trigger a rebuild at the next refresh.

GTNH publishes set labels even when a world's marker geometry is empty. Visible layer names therefore do not prove the geometry belongs to the active world. Its browser loads `_markers_/marker_<world-id>.json` relative to the configured marker URL; inspect `sets.<set-id>.areas`, `.lines`, and `.markers` for geometry. GTNH 0.3.47 filters this JSON using the marker's raw world ID, while newer source uses its normalized ID. Xenofactions supplies the normalized ID to both, preserving publication for names containing `/`, `[` or `]`. Nonpersistent markers are still included in web JSON and updates; persistence only controls marker save/reload.

## JourneyMap client overlay

JourneyMap is optional. JourneyMap 6.0.x uses its client API 2.0.0 plugin to show faction claims and zones, plus the selected map boundary when enforcement is enabled and both sites during a running Search and Destroy pairing. The enabled selected-map boundary is visible to all players in its dimension even before a match starts and without `/tdm overlay on`, including administrators. The optional overlay preference adds only the active gamemode's applicable spawn markers and nearby zone details. The API is compile-only and is not bundled into Xenofactions or required on the server. The 5.2.x reflection hook remains for older clients. When JourneyMap is unavailable or cannot accept polygons, the same public shapes appear on the standalone HUD map. Right Alt, configurable in Controls, toggles in-world border effects and the standalone HUD map and defaults on; it never hides JourneyMap shapes or changes server-side enforcement.

Nearby territory sparks refresh in four stripes over 12 server ticks, with at most 32 edges sent to each player every three ticks. Territory and local TDM border sparks share a client queue: at most 96 new particles per tick on All, 48 with reduced segment density on Decreased, and none on Minimal. Sparks require loaded nearby columns within 96 horizontal blocks; visual work expires after 12 client ticks and clears on world changes or disconnect. These limits affect presentation only. Client and server must use matching border packet implementations.

Category: `XENOFACTIONS_09B_JOURNEYMAP_CLIENT`

The automatic Earth day/night background preload is separate from these claim
overlay preferences and Right Alt. It targets only the inspected Deadhour Forge
1.7.10 JourneyMap 6.0.7 build, adds no configuration, and requires the active
Earth overworld profile and offline imagery. See
[Earth preload and world identity limits](XFEARTH_WORLD_TYPE.md#optional-journeymap-earth-preload).

| Key | Default | Meaning |
|---|---:|---|
| `showMinimapClaims` | `true` | Render faction city claims on rectangular or circular minimaps. |
| `showFullscreenClaims` | `true` | Render faction city claims on the fullscreen map. |
| `journeyMapShowTerritoryLabels` | `true` | Render one readable city-name label per city territory on enabled JourneyMap claim overlays. Labels use the city name, not internal faction or city IDs. |
| `claimFillOpacity` | `0.18` | Faction-color fill opacity, clamped to `0.0–1.0`. |
| `claimBorderOpacity` | `0.80` | Exposed perimeter opacity, clamped to `0.0–1.0`. |
| `claimBorderWidth` | `1.0` | Perimeter width, clamped to `0.25–10.0` pixels. |

## Retained legacy machine settings

`XENOFACTIONS_10_MACHINES_POWER` keeps its historical category name for configuration compatibility, but contains no power behavior. Supported settings are fishing intervals (`superFishrate`, `goodFishrate`, `averageFishrate`, `crapFishrate`), net `jamRate`/`whaleChance`, University `uniRate`/`uniJamRate`, Temple `templeRate`, and Production Line `factoryRate`/`factoryJamRate`. Defaults remain 20/40/60/1000000 seconds, 900 seconds/5 percent, 180/900 seconds, 1800 seconds, and 180/900 seconds respectively.

The same category now exposes item quantities, each defaulting to **5** instead of the former hardcoded **1**: `fishingOutputAmount` (fish or whale meat per catch), `grainmillOutputAmount` (flour per wheat), `universityOutputAmount` (research), `factoryOutputAmount` (cogs) and `templeOutputAmount` (scrolls). Range: 1–64. Explicit values are used directly without another multiplier. Production/jam intervals, whale chance, grainmill's 60-tick processing time and one-wheat input, and inventory/stack limits are unchanged. Automatic generators fill only available output space; grainmills wait for enough room for their complete batch before consuming wheat.

Item tooltips, NEI machine information and hovering machine GUI titles show placement dimensions in blocks as width × height × depth. Multiblocks derive sizes from their inclusive placement extents; variants use their actual block definition. Foundation, surrounding water, sky and other operational clearance remain separate requirements: the single-block Fishing Net and City Center are displayed as 1 × 1 × 1, rather than presenting their operating area as placed blocks. University/Production Line are 9 × 4 × 9, Grain Mill 3 × 6 × 3, Temple 17 × 6 × 13, Statue 3 × 4 × 3 and Warp Tent 5 × 3 × 5. Rotating a structure swaps its world X/Z extents without changing its local width/depth. Large faction flags show their required cloth clearance envelope (3 × 11 × 1), including the 1 × 11 × 1 pole. Machine login snapshots carry server quantities and prestige values for tooltips/NEI; updated clients and servers must match.

World/entity restrictions, entity effects/immunity, cave sickness, rectangular border, vanilla stone drops, chat filter and diagnostic logging remain configurable. Radar/forcefield, RF/oil/nuclear, weapons, stock/tax, obsolete-building rates and Builder Depot settings are no longer read or generated. Old keys may remain in an existing Forge config and are ignored; no destructive rewrite is performed.

The `enableChatFilter` setting defaults to `true`. On the server, it applies the existing substitutions to public player chat before Minecraft delivery and optional external relay. Clients recognize these finalized public messages and do not filter them again; their local toggle cannot undo server filtering. The client setting still filters other displayed text and translation arguments while preserving component formatting, click actions and hover events. URL clicks use Minecraft's normal Web Links and Prompt on Links settings; the filter leaves action targets and hover contents unchanged.

## AFK announcements and admin chat prefix

Category: `XENOFACTIONS_26_PLAYER_PRESENCE` in `hfr.cfg`.

| Key | Default | Meaning |
| --- | ---: | --- |
| `enableAfkDetection` | `true` | Track player input and idle state on the server. Disabling silently clears tracking. |
| `enableAfkAnnouncements` | `true` | Broadcast `<player> is now afk` and `<player> is no longer afk` exactly once per state change. |
| `afkTimeoutSeconds` | `300` | Idle seconds before AFK; range 1–86400. Independent of vanilla idle kicking. |
| `enableAdminPrefix` | `true` | Add a red `[Admin]`, reset formatting, then preserve the existing name/message colors for server-recognized operators in public, faction and alliance chat. No permissions are granted. |

Level-3 operators and the console can use `/xc presence show` and `/xc presence set <key> <value>`; boolean values are `true`/`false`. These actions also appear individually in the command menu. Settings are validated and atomically persisted before becoming active; rejected/failed writes retain the previous active and saved values. Changes apply immediately. Direct file edits use the normal startup configuration load; there is no file watcher.

The server owns elapsed-time timers and broadcasts. Vanilla's explicit-activity timestamp covers chat, commands and gameplay interactions; intercepted reward phrases, creative inventory actions and flight toggles also count. Updated clients send a throttled, empty activity intent for movement controls (including riding/swimming/flying) and actual mouse input equivalent to at least one degree of cumulative yaw/pitch at the current sensitivity. Position and rotation updates alone never count: gravity, water currents, knockback, passive vehicle travel, teleports, keepalives and passive server updates do not reset AFK. Activity intents carry no positions, permissions or gameplay state. Network callbacks only coalesce connection identities; the server END tick applies them to the current connection. State survives respawn/dimension changes, clears on disconnect/server shutdown, and disconnecting never broadcasts a return. Normal activity detection can be delayed by up to one second for input intents plus server tick lag. Client/server builds must match for input detection and machine display packets.

## Custom stone drops JSON

`/stonedrop` saves entries to:

```text
config/stonedrops.json
```

Each saved entry stores item registry name, metadata, stack size, chance, optional NBT string, and optional `minY`/`maxY` range.

During post-initialization, Xenofactions checks the Forge mod loader for the optional HBM mod.
When HBM is loaded, Xenofactions reads `com.hbm.config.MiningConfig.excavatorBedrockDrops`
with reflection after HBM has initialized it. Entries must use HBM's documented
`modid:item metadata minimumAmount maximumAmount` format, are resolved through the Forge 1.7.10
item registry (including block items such as `hbm:tile.ore_uranium`), and are added at a
one-percent chance per normal-stone block within the generated material-based Y range.

Administrator entries loaded from `config/stonedrops.json` are preserved. Missing automatic HBM
entries are appended without duplicating an existing item/metadata/NBT combination. An existing
empty JSON list is treated as an intentional administrator configuration and is not overwritten.
An empty file or malformed JSON is logged as a recovery case; if valid HBM entries are available,
the malformed file is backed up and `stonedrops.json` is rewritten safely with the recovered
automatic entries. The success log message is:

```text
[XF] Registered <count> automatic HBM stone drops from MiningConfig.excavatorBedrockDrops.
```

## Faction creation cooldown data

Regular leader-run `/c disband <faction name>` writes faction creation penalties to `clowder_faction_creation_cooldowns.json` in the server root. Entries are absolute real-time expiration timestamps keyed by player UUID where possible, with last-known names and normalized-name fallbacks for unresolved offline profiles. These cooldowns block only `/c create`; applications, invitations, and joining are unchanged.


The cooldown JSON contains `uuids` and normalized `nameFallbacks`; entries contain epoch-millisecond `expiresAt` and `lastKnownName`. `/xc clearcreationcooldown <player-or-uuid>` (alias `resetcreationcooldown`) works for online/offline targets and saves successful changes immediately via temporary-file replacement.

## XENOFACTIONS_18_EARTH_WORLD

Phase 1's template-backed Earth type is controlled by `enableEarthWorldType`, `earthWorldTypeName` (canonical value `xf_earth`), `earthRequireProfile`, `earthMissingChunkPolicy` (`FAIL` or `VOID`), `earthBoundaryMode` (`OFF`, `PROFILE`, or `CONFIG`), `earthAllowProfileMinecraftVersion`, `earthLogFallbackChunks`, and `earthBoundarySafetyMargin`. `PROFILE` derives the dimension-0 border from the validated save profile; `CONFIG` preserves the legacy administrator border coordinates; `OFF` does not activate a profile border. See [XFEARTH_INSTALLATION.md](XFEARTH_INSTALLATION.md).
# TDM event sounds

The `20_tdm` configuration category accepts string lists for `ctWinSounds`,
`terroristWinSounds`, `ctRoundStartSounds`, `terroristRoundStartSounds`, and
`bombPlantedSounds`. Entries are Minecraft sound IDs; one non-blank entry is
selected randomly for each authoritative event. Empty lists disable that event.

The bundled defaults are written by Forge in its 1.7.10 string-list syntax:

```cfg
I:tdmSoundConfigVersion=1
S:ctWinSounds <
    hfr:tdm.ct_win1
>
S:terroristWinSounds <
    hfr:tdm.t_win1
>
S:ctRoundStartSounds <
    hfr:tdm.ct_round_start1
>
S:terroristRoundStartSounds <
    hfr:tdm.t_round_start1
>
S:bombPlantedSounds <
    hfr:tdm.bomb_plant1
>
```

`tdmSoundConfigVersion` is an internal migration marker. When version 1 is first
applied, a missing, empty, or blank-only sound list is repaired to its bundled
default, while every non-empty custom list is preserved. After the marker is
written, administrators may intentionally clear any list to disable that event;
later loads and reloads preserve the empty list.

## TDM FFA and economy

`/tdm map mode <map> ffa` sets the legacy default to round-elimination Free-For-All; `/tdm map voteable <map> ffa on` can add it as another pairing while keeping the same physical map. Add neutral spawn locations with `/tdm map addspawn <map> ffa ffa`; these are stored in the FFA set, independently from the TDM and Search and Destroy team sets. Existing map saves are migrated to the former default mode. An empty FFA set never borrows team spawns unless an explicit spawn fallback is configured.

TDM maps persist non-negative economy rewards. Missing fields use these defaults:

- `roundLossBuyScoreReward`: `1`
- `killBuyScoreReward`: `2`
- `roundWinBuyScoreReward`: `3`
- `bombPlantBuyScoreReward`: `1`

Configure them with `/tdm map lossscore <map> <amount>`, `/tdm map killscore <map> <amount>`, `/tdm map roundwinscore <map> <amount>`, and `/tdm map plantscore <map> <amount>`. Zero disables an individual reward. The loss reward is finalized before the next buy period: the losing team receives it in team modes, every non-winner receives it in FFA, and an FFA draw awards it to all captured active competitors. Legacy `roundStartBuyScoreReward` map data is read as the loss reward when the new field is absent, but saves write only `roundLossBuyScoreReward`. A successful authoritative bomb plant pays the individual planter exactly once when the tracked bomb enters the planted state. `/tdm map economy <map> false` disables map economy as a whole.

Bundled sound event IDs are `hfr:tdm.ct_win1`, `hfr:tdm.t_win1`, `hfr:tdm.ct_round_start1`, `hfr:tdm.t_round_start1`, and `hfr:tdm.bomb_plant1`. Each configured event accepts a variant list; blank entries are ignored and an empty list disables playback.
The values name sound **events**, not `.ogg` files or paths. An ID without a namespace uses `hfr`; an explicit namespace (for example `othermod:custom.roundwin`) is preserved. The server selects one variant and sends an explicit playback packet to each eligible player in the same world/dimension. Operators can exercise that same path with `/tdm testsound <ctwin|twin|ctstart|tstart|bombplant>`; start tests target only the operator, while win and plant tests use normal global TDM recipients. If an event is disabled, the command reports the event type, property name, raw values, and normalized effective values. Enable legacy `enableDebugLogging` to log the trigger, configured variants, normalized event ID, recipient count, dispatch route, and client playback.

## TDM map scoring and purchases

Non-Search-and-Destroy maps can override their point-score victory threshold with `/tdm map pointlimit`; the existing timer remains an independent ending condition. DEATHMATCH stores team point scores and FFA stores per-player point scores. Both reset at a new match/map and are never spendable.

Killstreak support is opt-in per DEATHMATCH/FFA map (`killstreaks`, default `false`), with one kill-score unit awarded for each server-validated enemy kill by default. Unspent kill score persists through deaths but resets at a new match/map. Purchased rewards are queued and assembled after the next kit so kit reconstruction cannot erase them. Utility and killstreak definitions persist in the world save's `tdm_purchasables.txt`; utility costs use Search and Destroy buy score, while killstreak costs use only kill score.

## Ender chest restrictions

Category: `XENOFACTIONS_21_ENDER_CHEST` in `config/hfr.cfg`. These rules are enforced by the server when a player opens a vanilla ender chest block. They are disabled by default, so existing behavior remains unchanged until `enabled=true`. All six settings are global to this server installation, across all dimensions, factions and players; quantities are counted separately in each player's ender inventory. There are no world, faction or player overrides or bypass permissions.

Use the level-3 `xclowder` administrator commands below (also available through `/xclowder enderchest`). Every command supports the server console and requires the same permission, including inspection. Setting names and boolean/mode values are case-insensitive; registry names must match registered items exactly. Tab completion supplies actions, setting names, boolean/mode values, `-1`/`0` for the total and `clear` for lists.

```text
/xc enderchest
/xc enderchest show [setting]
/xc enderchest set enabled <true|false>
/xc enderchest set mode <blacklist|whitelist>
/xc enderchest set maxTotalItems <quantity>
/xc enderchest set itemRules <modid:item[@metadata] ...|clear>
/xc enderchest set storageDenyList <modid:item[@metadata] ...|clear>
/xc enderchest set itemQuantityLimits <modid:item[@metadata]=quantity ...|clear>
```

`show` without a setting displays all six. Each list command replaces the entire named list with space-separated entries; `clear` empties it. Metadata must be an integer from 0 through 32767. Quantities must be integers from -1 through 2147483647: -1 means unlimited and 0 prevents deposits. Unlimited per-item entries remain configured but impose no active limit. Overlapping per-item selectors retain the existing behavior: every matching active limit applies. A whitelist with an empty item list rejects all new item deposits. The storage deny list takes priority in both modes.

The entire replacement is validated before any setting changes. Malformed selectors, unregistered items, unsupported values, overflow, extra scalar arguments and incomplete commands are rejected with usage text. Missing optional-mod items already present in the file remain configured and are ignored by enforcement, as before; commands cannot add unregistered items. The output gives the effective value, global scope and enabled state, plus the configured value when it differs from the active rules.

Accepted command changes use the existing Forge `Configuration.save()` path and the same ender-rule loader and registry validation as startup. They apply immediately and persist in `hfr.cfg` across restart; Forge reports file-write failures in the server log. This does not add a general config reload command. Direct file edits still require a server restart; do not edit the file concurrently with commands, because saving writes the loaded configuration.

Already restricted containers stay open and use the updated settings on their next server click, preserving their existing snapshot/rollback behavior and allowing withdrawals. Enabling rules closes any open unrestricted `ContainerChest` backed by that player's ender inventory across all dimensions; players must reopen it to obtain the restricted container. Closing uses the normal server `closeScreen()` path: chest contents and inventory slots are retained, and a carried cursor stack is dropped normally for pickup rather than deleted. Disabling rules requires no reopen. Ordinary chests are not closed or changed.

| Key | Default | Meaning |
| --- | --- | --- |
| `enabled` | `false` | Enable deposit checks. |
| `mode` | `blacklist` | `blacklist` rejects `itemRules`; `whitelist` accepts only `itemRules`. |
| `itemRules` | empty | Registry names, optionally followed by `@metadata`. |
| `storageDenyList` | HBM portable storage listed below | Always denied, even in whitelist mode. |
| `maxTotalItems` | `-1` | Maximum sum of stack sizes across all 27 slots. `-1` is unlimited and `0` prevents deposits. |
| `itemQuantityLimits` | empty | Entries of `registry_name[@metadata]=quantity`, counted across all ender chest slots. `-1` is unlimited and `0` prevents deposits. |

Example Forge configuration entries (retain the generated `storageDenyList` to keep the HBM defaults):

```cfg
XENOFACTIONS_21_ENDER_CHEST {
    B:enabled=true
    S:mode=blacklist
    S:itemRules <
        minecraft:diamond
    >
    S:maxTotalItems=128
    S:itemQuantityLimits <
        minecraft:wool@14=16
    >
}
```

For example, Forge list values can include `minecraft:diamond`, `minecraft:wool@14`, and `minecraft:diamond=16` in `itemQuantityLimits`. Names are exact registry names, including the mod prefix; translated names and numeric IDs are not accepted. An entry without `@metadata` matches every metadata value. When both a general and metadata-specific limit match, both limits apply. The storage deny list takes priority over whitelist entries, followed by item rules and quantity limits. Limits count outer item stacks only; they never decode the contents of bags or crates.

The default `storageDenyList` covers confirmed HBM registry items `hbm:item.plastic_bag`, `hbm:item.containment_box`, `hbm:item.kit_toolbox`, `hbm:item.kit_custom`, and the carried-content block items `hbm:tile.crate_iron`, `hbm:tile.crate_steel`, `hbm:tile.crate_desh`, `hbm:tile.crate_tungsten`, `hbm:tile.crate_template`, and `hbm:tile.safe`. HBM's bags and kits store contents in item NBT; its storage crates and safe write slot NBT to the dropped block item. Their empty and filled forms have the same registry names, so both are blocked.

Saved contents are retained when these settings change. Players may withdraw items that now violate rules and may move contents without increasing restricted quantities. A rejected GUI deposit restores the ender chest, player inventory, and cursor, then sends a reason and a fresh inventory update. The default protection covers vanilla ender chest block interactions; integrations that directly mutate `EntityPlayer.getInventoryEnderChest()` or open their own container are outside this GUI interception path.


Validation compares each completed server click with its immediate prior contents. Withdrawing already stored restricted items remains allowed, but redepositing them is checked as a new deposit. Rejected clicks restore ender contents, player slots and cursor together. Optional HBM defaults use its confirmed legacy registry names; missing optional items are ignored.


## Client rendering and diagnostics

Border effects, faction-image metadata/bytes and auxiliary particle packets enter through common handlers and the sided proxy. Forge constructs clientbound handlers during dedicated-server startup as well; keep client execution and its generated lambda methods in `ClientProxy`. Its `ClientRenderSession` scheduling retains connection, dimension, world and generation checks, with the border subscription checked before queueing particles. This startup fix preserves packet IDs, formats and particle budgets.

Static TESR models use shared retained GPU geometry (core OpenGL 1.5 or ARB VBOs, with retained display lists on hardware without either or after a failed buffer upload). Resource reload rebuilds geometry and finite model bounds; disconnect/world unload releases model buffers. Chunk-baked fortifications, livestock models and the officer chest keep their native rendering paths. Model replacements accept triangle/quad OBJ faces, including negative indices; unsupported larger polygons log a resource-and-line error and omit that model. Preserve group names used by flags (`Pole`, `Flag`) and capture points (`Base`, `Point`). With an active OpenGL shader program, OBJ draws submit the same prepared geometry through Forge Tessellator hooks for shader attributes; normal rendering retains its GPU buffers. This compatibility path keeps prepared OBJ vertex data until the model is released or rebuilt. The former Forge OBJ models retain flat normals and the 0.0005 UV inset; former HFR models retain authored normals and uninset UVs.

Flag images share up to 256 reusable 64x64 texture slots. Wall art shares up to 64 slots within a 64 MiB pixel budget; up to eight requests and 8 MiB of incomplete image bytes are retained. PNG decoding uses bounded background workers; uploads, eviction and registry replacement run on the client thread. Wall-art requests expire after 15 seconds and retry on demand, with request tokens and connection/world checks rejecting stale replies. Resource reload clears downloaded GPU images, which are loaded again as needed. These are client presentation limits, not changes to server image storage or saved artwork. Install matching client and server builds: dimension fields and wall-art request tokens change the rendering packet format.

For troubleshooting, add `-Dhfr.renderMetrics=true` to the client JVM arguments before launch. Every ten seconds the log reports XF TESR calls, retained draw calls, uploads/deletes, reloads, selected/total map claims, snapshot preparation nanoseconds, grainmill draws/structure checks, XF model texture bindings, resident flag/art textures, slot limits, pending wall-art requests and border particle creation. TESR array order is temple, university, factory, net, prop, capture point, flag, large flag, conquest flag, grainmill, faction decoration, debug truck (remaining entries reserved). Counts cover executed XF paths; Forge frustum rejections and other mods' texture bindings are not intercepted. Counters are disabled by default and are diagnostic evidence, not a benchmark or FPS estimate.

