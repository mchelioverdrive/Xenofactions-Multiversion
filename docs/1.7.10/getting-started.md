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

The worker fetches suitable replacements, retains metadata/NBT and partial transfer remainders, and rechecks a supply wait within 2.5 seconds (direct inventory changes wake it sooner). It resumes with the same cursor after restocking; directly supplied tools/torches can cancel an unnecessary empty-handed return to storage. Inventory slot changes no longer erase mining progress; changing the actual tool or target still resets it, and repeated Start while already running preserves progress. It places supported torches near itself when local combined light is below 8, respecting faction ownership and Forge placement cancellation, and consumes supplies only after accepted placement. A blocked lighting position has its own status. Loaded workers remain paused until Start.

The worker still walks to storage and mines real blocks top-down through Forge survival harvesting with a FakePlayer, native hardness and durability. Source tracing confirms this raises BreakEvent into the existing loaded custom stone-drop table. For Miner harvests only, the existing reward handler is deferred until the actual harvest succeeds and the event remains uncanceled; it rolls once, at the mined block's Y, preserving configured chances, quantities, metadata, NBT and Y limits alongside ordinary drops. Cargo collects the resulting real item entities and delivers them to storage. There is no separate Miner loot table or offline production.

Mining requires faction-owned land and continued assigning-member authority. Containers, tile entities, faction infrastructure and liquids remain protected; bedrock/negative-hardness blocks are skipped and advance the cursor. Limits remain 64×64 horizontally, world-height depth including Y:0, 262144 total blocks and 128 horizontal blocks from storage to every area corner. Work remains top-down; hidden or unreachable ordered work triggers a scan of at most 64 current-layer candidates per work tick, including blocks with approachable standing positions beyond current mining reach. Alternatives never skip the unfinished primary block. Native pathfinding travels to supported standing positions near work/drops/storage, rather than into a chest or solid target. Distant travel uses waypoints no farther than eight blocks horizontally (also bounded by native search range), with up to four native searches per work tick, rotating approaches, twelve failed approach rounds and a 128-waypoint travel bound. Actual movement preserves a useful path even on detours; a hundred ticks without movement triggers another approach. Scanning air does not cancel travel. Unloaded path-cache chunks wait without being loaded. A terminal navigation failure retains job/items, names its destination and reports missing footing/headroom, partial path endpoint, stalled travel, search limits or failed return access; it is separate from Pause and completion. Selection/install logs are limited to one per category per second; terminal navigation logs to one per Miner per minute, including position, task, cursors, destination, waypoint, range, bounds and failure detail.

New multi-layer assignments at least 2×2 retain one original terrain block per depth as a perimeter staircase, anchored toward storage. These blocks are deferred, not treated as completed: after ordinary layers, the Miner backs up and harvests the staircase from bottom to top, retaining its cleanup cursor in job NBT. Before harvesting, it verifies a reversible storage route with the proposed block absent. Cached route footing/headroom is rechecked and invalidated by excavation or external changes; rebuilding searches at most 8192 supported positions inside the area/storage envelope plus sixteen blocks. Failed return checks retry before selecting another target. Movement rejects irreversible drops, and mining never removes the Miner's current support. Narrow areas, natural holes, blocked ramp entrances, old excavations and external terrain changes may still require player-provided access; no blocks are generated or placed to manufacture a staircase. Existing saved jobs retain their bounds/cursor and receive return-safety/navigation checks without assuming an unrecorded staircase still exists. No teleporting, expanded mining reach, liquid draining or chunk loading is used. Safe refusal or a bounded search failure is not proof that no possible route exists.

Storage overflow retains cargo and revisits real world drops, subject to normal despawn/pickup. Confirmed area replacement retains outstanding drop coordinates and collects them before excavating the new area, without reharvesting the old block or rerolling rewards. Completion requires the ordinary cursor, deferred staircase pass, outstanding drops and final cargo deposit to finish. Existing entity IDs, faction/employer, job/cursor, cargo, torch and equipped-tool NBT remain compatible; old configured jobs infer their station assignment. Permission/protection, containers, liquids, rejected harvesting, unsuitable tools, lighting and blocked chest lids keep their separate reasons and supply behavior. Compilation and source checks cannot establish in-game completion: multiple layers, the staircase cleanup, repeated storage returns, narrow/irregular areas, protection cancellation, restart/overflow and integrated/dedicated operation require runtime validation.

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
