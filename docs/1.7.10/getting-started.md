# Getting Started with Xenofactions

This guide explains the basic gameplay loop for a new player on a Xenofactions server.

Open the faction menu (K by default) for **Start here** and **Help**. These pages keep their selected page when revisited. Buttons use Tab/Shift+Tab or arrow keys for selection and Enter/Space for activation; mouse clicks, the wheel and on-screen scroll arrows are also available. Descriptions stay below their actions. Action forms support Tab/Shift+Tab and Enter; Escape leaves a setup form for normal play.

The welcome introduction shows **Open world map: <current key>** and **Open faction menu: <current key>** below its heading. These use XenoFactions' own bindings in Minecraft Controls, including mouse buttons and **Unbound** for an unassigned action. The new **Open Xenofactions World Map** binding starts unbound; assign it in Controls or open World map from the faction menu. It is separate from other mods' map bindings. Reopening the welcome after changing Controls shows the new assignments; both hints stay above the paginated introduction.

Automatic welcome display is an installation-wide client preference stored in the exact file `tutorialflag` in Minecraft's game directory (`mcDataDir`). Its presence suppresses the automatic welcome. Its absence queues the welcome once per connection, after login synchronization and when existing screen/setup priorities allow. Dismissing the introduction (including Escape), **Start here**, and **Explore map** create the marker through the same completion path. Delete `tutorialflag` and reconnect to see the welcome again, or confirm **Replay welcome** in Help to delete it and queue the welcome in the current connection. File errors are logged without crashing. **Modpack exports must omit `tutorialflag` so new installations see the welcome.**

Old server-side `OnboardingComplete` values are ignored and left untouched; they never recreate the local marker. Welcome dismissal and replay do not change server-owned faction setup progress, flag delivery receipts, faction membership, claims, inventory, achievements or daily rewards. Start here and Help remain manually available regardless of the marker. Faction setup synchronization always runs independently of the marker; updated clients and servers must match the revised faction setup packet.

Successful faction creation, through either a command or its action form, starts optional guidance. The server opens the existing **Get a City Center** form, confirms delivery of the named flag, closes the form for world placement, then opens **Set Faction Home** only after accepted placement establishes a city. The small instruction panel stays at the top left during play; narrow action forms reserve space above their content. A server-confirmed valid faction home queues a short menu saying **Run /xmulti to get a multitool.** It explains the free Alloy Multitool's block harvesting and lack of durability. **Play** or Escape acknowledges this final guidance; the screen never runs the command or grants an item. Rejected commands do not trigger it, and another active screen delays opening. Repeated snapshots and later home changes do not reopen an acknowledged step for the same faction.

**Resume faction setup** and **Dismiss guidance / Show guidance** are available in Start here and the Help topic list. Closing a form keeps the confirmed step. Dismissal is saved and never prevents normal play; reopening resumes the saved step without repeating delivery. Existing faction membership alone does not start guidance. Failed commands keep the form and its arguments, with the server's reason; resolve that reason and retry. During placement, a rejection keeps the flag and the placement instruction. Check the server's chat message for foundation, territory, dimension or protection restrictions.

The initial delivery step and inventory are committed together in the existing UUID player snapshot. Repeated claims, reopened forms and reconnects cannot issue another setup flag while its placement is outstanding, including with survival recipes enabled. Find that flag in your inventory, storage or dropped items before trying to place it; the guide does not replace lost flags. Costs, rank checks, first-city capital designation and placement rules still come from the existing server handlers. No tutorial completion is required to play. Active visible setup and its completion message take priority over automatic daily-wheel openings; queued daily rewards remain available after completion or dismissal, and `/xdaily` still opens them manually.

Guide state is the optional `FactionSetup` compound (`Faction` UUID, confirmed `Step`, and `Hidden`) under `ForgeData.PlayerPersisted.xfDailyRewards`. It uses the existing player save/load/clone path and atomic player-file replacement. Client intents show, dismiss or resume guidance and acknowledge the final multitool information; they cannot set gameplay progress or grant items. Historical completed records keep `Step=4` and stay complete. Only newly confirmed homes enter `Step=5`; its acknowledgement saves the existing terminal `Step=4`. A disconnected pending step resumes on login rather than replaying claim/flag/home steps. Accepted city/home world changes retain Minecraft's existing world-save behavior; the player and world files are not one crash-atomic transaction. Updated clients and servers must match for the extended onboarding snapshot. In-game layout at different scales, cancellation, reconnect/restart, full inventories, storage failures and dedicated-server operation still require runtime verification.

## Native faction workers

Builder Contracts, Farmer Contracts (three variants) and Miner Contracts are available in the Xenofactions creative tab or through ordinary administrator item distribution. They are not granted by setup, `/xmulti`, crafting or city founding. Contract use requires an Officer/Leader on faction-owned land. A Builder Contract is used on a Builder Depot; farmer/miner contracts are used against a block face with two air blocks for the worker. Contracts are consumed only after a successful survival spawn. The native Forge entities require no NPC mod.

The **Builder** and its supporting Depot/job/schematic system were recovered from the local `e4cba6f` parent (the implementation introduced in `3d4eb18`), not from CustomNPCs or external configuration. The historical `entity_hfr_faction_builder` entity ID/name, `tileentity_hfr_builder`, `tile.builder`, `item.builder_contract`, faction/job/depot UUIDs and 27 carried inventory slots are retained. Unloading a worker does not authorize a replacement. Right-click the worker for its inventory/status and Recall/Pause/Resume; right-click the Depot for planning, materials and queue management. Depot slot 0 stays hidden legacy storage, with construction materials in slots 1–27. Place server `.schematic` files in `config/schematics`, select/position/rotate/mirror a plan, preview it, supply real materials, and start. Accepted jobs and normalized schematics use the historical `hfr_builder_jobs` and `hfr_schematic_store` world data. Optional Schematica client import remains optional; instant printing, removed machines and their dependencies are not restored. Schematics requiring missing blocks stop rather than granting substitutions. World/entity saves retain normal Minecraft durability.

The **Farmer** restores the historical `EntityFarmer` (`entity_hfr_slave`) and its three biped skins. Every five seconds of supplied-food time it works nearby mature crops at its own height, replants supported crops, moistens farmland and occasionally tills dirt/grass. The original above-Y=40/open-sky requirement, initial five-minute fuel, food nutrition × 1200-tick refill, yields and crop specialization are retained: O'Brien favors potatoes, Nghiêm also harvests upper sugar cane and favors rice, and Davis also harvests pumpkins/melons. Right-click with food to feed; otherwise right-click to collect its stored produce. A full player inventory leaves the remainder on the farmer. Operations and collection are server-owned and limited to its faction; legacy saved farmers without a faction stay idle until an Officer/Leader binds them by interacting on that faction's land. Saved crop counts, fuel and type are retained. No new crop or world generation is added.

The **Miner** uses an opaque, normally lit vanilla biped and displays its actual equipped pickaxe. First authorized interaction supplies two UUID/dimension-bound wands. The receipt survives reconnect/death; **Recover / bind wands** reuses existing wands for this Miner and replaces missing ones. Both grants are checked for free inventory space before changing anything; full inventories retain the previous binding and can retry. Wands identify the Miner and controls in their tooltips. A bound Miner must remain loaded in the same dimension, but the player need not stand beside it while selecting. After a reload, lookup examines at most 4096 loaded entities if its cached entity ID changed; if it cannot resolve the Miner, use Recover / bind wands beside it.

Right-click a vanilla chest with the **Miner Station Wand** to assign supply/output storage without opening it. Both halves of a double chest are used and must be loaded and on faction land; blocked chest lids prevent access. Faction Builder Depots remain supported, excluding their legacy hidden slot. Station assignment pauses the worker and shows coordinates/dimension in its GUI. Use the **Mining Area Wand**: left-click corner A, right-click corner B for an inclusive 3D box, or **Shift-right-click B** for a horizontal footprint extending from the higher of the two selected Y values down through **Y:0**. This uses the configured Sneak control's state at the click, including rebound mouse/keyboard controls; releasing Sneak before the server handles the packet cannot change that intent. Changing A retains the last B depth mode; a later ordinary right-click B intentionally returns to corner-height mode. Native interactions only suppress breaking/placing/opening and cannot overwrite the custom packet's selection. Reversed corners normalize automatically. Server feedback, tooltips and the GUI show the explicit depth mode, effective top/bottom Y and inclusive volume. The held wand previews the boundary and selected blocks.

Return to the Miner and press **Start / Resume**. Start resolves this Miner's UUID/dimension-bound area wand on the server, validates both raw corners, normalized bounds, station and faction permissions, and assigns and starts a first job in one action. Server-issued, persisted selection revisions prefer the most recently edited bound wand over older copies; conflicting selections with equal revisions (including old unversioned wands) remain ambiguous and are rejected. Incomplete, invalid, wrong-dimension and foreign-only selections also explain their rejection. Selecting the same area resumes without resetting its cursor or current running mining progress. A different area replacing unfinished work prompts once with **Replace & Start**. The server container retains the exact proposal, including depth mode, and the GUI shows those proposed bounds separately. Confirmation revalidates that stored proposal, the assigned job, station and permissions, then installs and starts it immediately, even if the wand subsequently moves, changes or is removed. To replace the proposal with a later selection, cancel with Pause and press Start again. Selecting alone leaves the old job unchanged; a failed Start preserves it and never silently resumes it instead. With no new selection, Start resumes the saved job without either wand. Installed bounds and mode are saved independently, and activation never clears wand selections.

The GUI exposes all existing 27 cargo slots, the actual equipped pickaxe with durability, a dedicated torch supply slot, and player inventory/hotbar with normal and Shift-click transfers. The right panel shows loading or a synchronization warning while awaiting server state, then started/paused state, the specific work or blocking reason, installed station and bounds before requests, task/target and the separate wand selection. Use its up/down buttons or mouse wheel to read wrapped details. Activation errors persist through polling; a successful action clears obsolete feedback. An unapplied selection or replacement proposal is not an installed job. Replacement confirmation belongs to the current server container and is discarded when it closes; changing the installed assignment requires fresh confirmation. The Miner sends its authoritative UUID in Forge spawn data: 1.7.10 otherwise creates a different client UUID and the required server UUID checks reject both Start and status requests. Actions and replies validate the current container window; replies retain connection/world/entity checks and a per-window sequence rejects older snapshots. Selection updates synchronize item NBT and any open Miner panel, activation replies carry the installed server state, and open panels also refresh once per second. Matching updated clients and servers are required for the expanded click packet.

Tools and torches can be supplied directly or through the station; resources can be retrieved. Blocking requests check equipment, cargo and the accessible assigned station. A missing usable pickaxe requests one suitable pickaxe; an insufficient tool shows the required harvest level. A dark work area with no available torches requests one torch to unblock lighting. The optional refill to 32 is displayed separately and never blocks work by itself. Missing/unloaded/inaccessible storage is an access problem, not proof of a supply shortage. An active job privately notifies its online employer of confirmed shortages, identifies the Miner and explains both supply locations; unchanged requests are deduplicated between reminders, with at most one reminder per Miner per 60 seconds, even across repeated Start clicks or reopening the GUI. Recipient faction permissions are rechecked. No open GUI is required. Fulfilled requests, completion and manual Pause stop reminders.

The worker fetches suitable replacements, retains metadata/NBT and partial transfer remainders, and rechecks a supply wait within 2.5 seconds (direct inventory changes wake it sooner). It resumes with the same cursor and retained alternative target after restocking; directly supplied required tools/torches can cancel a supply trip while usable cargo capacity remains, including a partially loaded trip. Start / Resume and opening the GUI never create a deposit merely because cargo is present. Inventory slot changes no longer erase mining progress; changing the actual tool or target still resets it, and repeated Start while already running preserves progress. It places supported torches near itself when local combined light is below 8, respecting faction ownership and Forge placement cancellation, and consumes supplies only after accepted placement. A blocked lighting position has its own status. Loaded workers remain paused until Start.

The worker still walks to storage and mines real blocks top-down through Forge survival harvesting with a FakePlayer, native hardness and durability. Source tracing confirms this raises BreakEvent into the existing loaded custom stone-drop table. For Miner harvests only, the existing reward handler is deferred until the actual harvest succeeds and the event remains uncanceled; it rolls once, at the mined block's Y, preserving configured chances, quantities, metadata, NBT and Y limits alongside ordinary drops. Cargo collects the resulting real item entities and delivers them to storage. There is no separate Miner loot table or offline production.

Mining requires faction-owned land and continued assigning-member authority. Containers, tile entities, faction infrastructure and liquids remain protected; bedrock/negative-hardness blocks are skipped. Limits remain 64×64 horizontally, world-height depth including Y:0, 262144 total blocks and 128 horizontal blocks from storage to every area corner. Work remains top-down; alternative current-layer targets retain unfinished primary work. Navigation uses collision shapes and the Miner's body for walking, steps up of at most one block, safe drops of at most two blocks with independently verified exits, and attached ladders. Partial surfaces such as slabs and stairs use their actual collision height. Ladder nodes need attachment and clearance, not ordinary floor support or onGround. Ladder, descending and partial-surface segments run through ordinary living movement/collision physics; vanilla receives at most six straight level edges or one full-block climb. Accepted ladder edges actually drive upward/downward motion and a lip exit; an idle working Miner holds its position on the ladder.

Candidate endpoints and actual arrival share reach, body clearance, footing or ladder attachment, elevation, visibility and target-identity checks. The baseline allowed a center exactly three blocks away while native navigation could finish within 0.6 blocks of that center, leaving the real Miner outside reach. Route ranking now adds a preference for endpoints within 2.35 blocks, leaving movement tolerance inside unchanged three-block reach. Valid fringe endpoints remain available. A completed route with a small reach/visibility/footing disagreement can attempt one short, supported, collision-checked final approach. It must pass actual arrival again before interacting. Failed endpoints are excluded for that target, and changed blocks or metadata invalidate travel. Logs identify the actual failed condition, target identity, actual position, selected endpoint, distance, ground/ladder state and visibility. The localized GUI separates the target, selected standing position, first planned descent, current waypoint, actual position and walking-rim/stepping/descending/climbing/construction/supply states.

A* compares complete route costs across interaction positions above and below the old surface and searches the whole bounded corridor for direct steps and existing rim entrances before constructing access. Each search expands at most 8192 positions, discovers at most 16384 and retains at most 4096 route positions; per-search surface caching is capped at 32768 entries. Work-route selection tries at most three endpoints with independent storage-exit checks. The exit may differ from the entrance and is checked with the target absent before descent and again before harvesting; entrance travel still uses the actual unmined terrain. Travel stays inside the footprint plus eight blocks or the existing sixteen-block station/drop corridors. Paths and nearby collision shapes are revalidated, searches use loaded blocks only, and native follower-neighbor chunks are checked before submission and each tick. A hundred ticks without route progress triggers replanning, capped at four rebuilds or three failed route attempts. Failed endpoints are bounded to 64 per target. Terminal failures preserve the job/items and use the existing one-minute logger limit. A search limit does not establish that every possible entrance is obstructed.

If a started Miner is displaced outside that corridor, or **Start / Resume** is used on an existing displaced Miner, it first shows **Returning to work corridor**. The previous combined outside/unsupported-start rejection happened before any route expansion; it did not establish a terrain obstruction. Recovery now searches for any reachable supported corridor entry instead of assuming the nearest geometric entry is accessible. Its fixed envelope is centered on the initial displacement position: a 64-block horizontal radius and at most 16 blocks vertically, including all candidate entries within that envelope. Each search retains the 8192 expanded / 16384 discovered node limits and a route cost of at most 256 level steps (height changes cost extra). At most three searches, including replans, and 2400 recovery ticks are allowed. Movement never recenters or enlarges these bounds. Recovery checks loaded terrain, support, body collision shapes, dry clearance and reversible one-block steps, and follows the same short verified native segments as ordinary travel. Its route is separate from work travel and the storage return cache. The GUI shows the recovery start, selected reachable destination (or that no entry has been selected), attempts and localized outside-corridor, unsupported-footing, obstruction, unloaded-chunk or search-limit reasons. Terminal failures use the existing once-per-Miner-per-minute logger and retain the job and items.

Recovery temporarily owns movement before work scans or inventory-driven storage decisions. Reaching a safe corridor position clears its temporary path and resumes the interrupted mining, drop collection or storage task on the next work tick, retaining the assigned area, cursor, pending drops, cargo, equipment, supplies and trip reason; partial cargo alone still does not trigger a deposit. The original job corridor remains authoritative for all normal routes. Pause cancels recovery and requires Start again. Reload retains the interrupted task and existing job/items, remains paused as before, and rebuilds recovery from loaded terrain on Start; transient recovery paths are never restored from disk. A bounded failure requires inspection of its actual reason, not respawning or reassigning the Miner. Recovery still needs in-game validation against the actual terrain, including displaced saved jobs, blocked nearest entries, chunk unloading, collisions and interrupted storage trips.

New multi-layer assignments at least 2×2 still retain one original terrain block per depth as a perimeter staircase toward storage. Deferred blocks are harvested bottom-up only when the common interaction and proposed-removal exit checks permit it. There is no extra blanket requirement to stand above the cleanup target when another valid exit exists. The safety cache remains storage-to-work, revalidates collision surfaces and ladder attachments, discards stale suffixes and can supply intact travel in either direction after validating the requested direction. Existing saved jobs retain their bounds, cursor and depth mode without assuming an unsaved staircase.

When existing terrain cannot provide a verified work entrance and exit, the Miner can plan a small supplied wall ladder, adding backing blocks where required. Construction considers up to 100 nearby column/face geometries and checks ranked proposals across work updates, one proposal per update. Endpoint validation checks at most three approach/exit pairs per update and retains the remaining endpoints; neither a fourth endpoint nor a fifth access proposal is rejected just because earlier alternatives failed. A proposal contains at most sixteen ladder levels / 32 cells and joins retained access on deeper layers. Its bottom includes the exposed target layer even when the worker stands above the construction ceiling. Construction can progress from above or below according to the reachable end. The first missing cell must be reachable on actual terrain without occupying its future collision shape; the complete proposed route must reach work and storage before it is selected. Each construction approach also verifies an exit with the current cell placed. Construction remains within the installed work bounds and faction-owned land. It does not dig a new tunnel, overwrite terrain/containers/liquids, generate free blocks, teleport, expand reach or load chunks.

Supply ordinary untagged ladders and, when backing is missing, plain solid stone/earth ItemBlocks such as carried cobblestone or dirt. Falling blocks, tile-entity blocks and tagged materials are excluded from automatic access consumption. Existing stairs/slabs are traversable; new access construction uses ladders and solid backing rather than placing stair items. The GUI and existing private employer reminder show the exact missing counts after carried and usable station supplies. Required access supplies may initiate a storage trip; optional torch refill still does not. If no station route is usable, the worker waits for direct supplies instead of treating inaccessible station items as available. Inventory changes wake that wait and permit another bounded station-route check. Placement rechecks loadedness, work bounds, ownership, edit permission, attachment, entity collision and native placement rules, then raises Forge PlaceEvent with a rollback snapshot. Only accepted, verified placement consumes one original item. Failure preserves the item and old target cell.

Placed ladder cells and backing reservations, pending construction order/index and interrupted storage intent are saved separately from the existing job and inventory. They survive Pause/reload and rebuild routes after Start. The selected plan also survives travel, missing-material waits and refused or obstructed placement; repairing the obstruction resumes that plan. Storage retains exactly the carried materials still reserved for construction, deposits other output and fetches the remaining needs. Access cells remain reserved during excavation and staircase cleanup, so the Miner does not mine its own ladder/backing; these access structures are deliberately left in the completed excavation. Missing recorded access can be repaired with supplies, including while recovering a blocked storage trip, then the retained task resumes. At most 1024 access cells are retained. Changed occupied/protected cells, unsupported placement, unavailable authorized room and exhausted planning bounds report their own failures. Existing excavations do not require a new Miner or reassignment. Actual shallow-pit entry, partial-block corners, ladder entry/lip exits in both directions, extensions/repairs, protection cancellation, material counts and restart/storage behavior still require in-game testing; source inspection and compilation do not establish traversal at the reported coordinates.

Route searches join nearby supported nodes from the Miner's actual body position, with collision-checked initial motion executed through normal physics. A grounded worker straddling a block edge is no longer rejected solely because the center of its floored coordinate lacks support. Actual and hypothetical return searches and cached routes use the same initial-connection check. Alternative current-layer blocks are selected only after verifying an approach, interaction position and post-harvest storage exit, or selecting a complete supplied-access plan. Failed blocks remain unfinished and are reconsidered after excavation, construction or a detected relevant change. Searching and access planning are separate activities; changing targets or rebuilding the same route is not mining progress.

Ordinary route retries use `xf.miner.state.recovering` ("Recovering a bounded route to the current task"); displaced-corridor recovery uses `xf.miner.state.corridor_recovery`. The old fallback's `xf.miner.state.working` ("Working") did not prove that a route was executable and is no longer emitted by that selection path. `xf.miner.nav.footing` identifies missing safe footing/attached ladder; `xf.miner.nav.access_blocked` and `xf.miner.nav.access_limit` describe unsuccessful or bounded access planning. The GUI puts activity, evaluated target and blocker first, retains final failure details while blocked and clears resolved arrival errors.

The unfinished job retains a separate limit of 24 failed execution attempts and 12000 active work ticks without useful progress across task transitions and alternate targets. Actual hardness progress, completed cursor advancement and first-time reached route positions advance the progress clock; harvests, accepted access placement and real storage transfers also clear the failure budget. Reaching a new route position can clear execution failures, but revisiting the same positions on a rebuilt route cannot. Supply/chunk/storage waits do not consume the active clock. Exhausted alternatives or bounds enter a stable started-but-blocked state instead of rerunning the same task. The Miner inspects up to 512 of at most 32768 recorded planner terrain cells per update (block identity, metadata and loadedness), relevant supply/station availability and its position. Detected changes automatically retry retained work or construction; unchanged conditions preserve the final blocker. Silent collision changes within a tile entity that do not change block identity/metadata are not detected by this terrain monitor. Start remains an explicit manual retry, and Pause/reload retain their existing stopped-until-Start behavior. Objective changes, execution failures, access attempts and failure resets have separate ten-second diagnostic limits; terminal diagnostics retain the existing one-minute limit. These bounds and source checks are not an in-game performance or traversal result.

The explicit work cycle is travel to unfinished work, mining, travel to storage with a recorded reason, depositing, required restocking, then automatic travel back to unfinished work. Trips begin only for cargo with no remaining stack capacity, actual output that cannot fit compatible item/metadata/NBT stacks, a required pickaxe, required dark-area torches, required access materials, or the final deposit. Occupied partial stacks are usable capacity; incompatible actual output can still require storage even when another item has stack space. Optional torch refill never initiates a trip. Capacity diagnostics include occupied slots, remaining partial-stack capacity and, for overflow, the actual output and its compatible remaining capacity. Full storage retains cargo/overflow and waits visibly; it never publishes a successful return with undeposited output. After deposit and required restocking, the storage destination, waypoint and retry state are replaced by the retained work/drop target without another Start or area reset. A still-valid target is reused; otherwise ordinary layer scanning selects unfinished work. Completion stops after the final deposit instead of starting another trip.

Overflow keeps copies of the actual uncollected item stacks in saved Miner NBT and removes those source item entities exactly once. It survives trips/reloads and drops with the Miner on death; it does not depend on normal world-item despawn. The GUI reports retained overflow separately from the 27 cargo slots. Pending drop-site coordinates are retained until their captured output is fully deposited. A final buffered deposit completes at storage without an empty trip back. Legacy coordinate-only saves still revisit their real drop site before new excavation; those old world items remain subject to normal despawn/pickup until collected. Confirmed area replacement retains outstanding drop coordinates and collects them before excavating the new area, without reharvesting the old block or rerolling rewards. Completion requires the ordinary cursor, deferred staircase pass, outstanding drops and final cargo deposit to finish. Existing entity IDs, faction/employer, job/cursor, cargo, torch and equipped-tool NBT remain compatible; old configured jobs infer their station assignment. Versioned task/reason, required lighting and overflow data preserve intentional trips; the old ambiguous Returning boolean is ignored on migration and actual capacity/supply/completion needs are evaluated on Start. Routes are rebuilt from loaded terrain after reload. Manual Pause and reload keep workers stopped until Start; automatic storage/supply/chunk waits remain started. The GUI derives specific mining/storage/deposit/restock/return tasks from server AI state and shows the current/last trip reason, final destination, waypoint and work target separately. Permission/protection, containers, liquids, rejected harvesting, unsuitable tools, lighting and blocked chest lids keep their separate reasons and supply behavior. Compilation and source checks cannot establish in-game completion: multiple layers, the staircase cleanup, repeated storage returns, narrow/irregular areas, protection cancellation, restart/overflow and integrated/dedicated operation require runtime validation.

Welcome dismissal remains the client-only `tutorialflag` preference and never resets worker or faction setup data. Updated clients and servers must match the expanded worker/setup packets. NPC pathfinding, resource gathering, GUI layout, saved entities and dedicated-server behavior still require in-game testing.

## 1. Join or create a faction

Create a faction:

```text
/c create <faction name>
```

Join an existing faction:

```text
/c apply <faction name>
```

Faction officers/leaders can review and accept applicants:

```text
/c applicants
/c accept <player>
/c deny <player>
```

Officers and leaders can also send `/c invite <player>` while the target is online. The target accepts with `/c join <faction name>`. Revoke an invitation with `/c uninvite <player>`, including while the target is offline. Invitations survive faction renames and server restarts, and joining a faction clears all pending invitations.

## 2. Claim your first city

Stand where you want the city center and run:

```text
/c claim <city name>
```

City rules are configurable by the server. By default, city radii are tiered from 2 to 6 chunks, city centers must be spaced apart, and city upgrades cost prestige.

Place the named City Center to found the city. Your first successful city becomes the faction's designated capital, separately from its upgrade level. Officers and leaders can run `/c sethome` only inside that capital. Inspect it with `/c capital`; leaders can change it with `/c capital set <owned city>`, with seven days between changes by default.

When survival recipes are enabled, craft a Global Market Terminal using `IRI / ECE / IRI` (I iron ingot, R redstone, E emerald, C chest). An Officer or leader must place it in your capital. Each faction gets one active terminal, and all members can browse the server's approved shops there. If the capital changes, place a replacement there; the old terminal stays inactive. Breaking the active terminal lets your faction place another.

Upgrade a city while standing in it:

```text
/c city upgrade
```

Name a claim:

```text
/c nameclaim <claim name>
```

## 3. Earn and spend prestige

Prestige is the faction economy. It is used for upkeep, war, warps, and city growth.

Useful commands:

```text
/c balance
/c deposit <amount>
/c setwarp <name>
/c warp <name>
/c warps
```

Withdrawal is unavailable. Old Prestige tokens have no acquisition path; depositing existing tokens remains a compatibility path into faction accounting.

## 4. Manage faction identity

Leaders can set presentation and messaging:

```text
/c color <hexadecimal>
/c motd <message>
/c flag <flag>
/c flag seturl <https image URL>
/c flag clear
/c flag reload
```

Use `/c listflags` to browse built-in flags. Imported image flags must pass the server's custom-flag configuration.

## 5. Diplomacy and war

Common diplomacy commands:

```text
/c ally <faction>
/c acceptally <faction>
/c unally <faction>
/c declarewar <faction>
/c ceasefire <faction>
/c acceptceasefire <faction>
/c peace <faction> {city}
/c acceptpeace <faction>
/c surrender <faction>
/c acceptsurrender <faction>
/c defendally <ally>
```

War declarations may be globally disabled by admins, and many war actions have configurable cooldowns and online-player checks.

## 6. Chat and maps

Faction chat:

```text
/cc <message>
/cc faction <message>
/cc alliance <message>
/cc public
```

Claim map item:

```text
/xmap
```

## Tips

- Use `/c help 1` through `/c help 6` in game for command pages.
- Faction names with spaces are stored with underscores in some command paths; when a command fails with a spaced name, try underscores.
- Ask server staff before using war, surrender, city transfer, or custom image features; many are tuned by server policy.
