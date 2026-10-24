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

Right-click a vanilla chest with the **Miner Station Wand** to assign supply/output storage without opening it. Both halves of a double chest are used and must be loaded and on faction land; blocked chest lids prevent access. Faction Builder Depots remain supported, excluding their legacy hidden slot. Station assignment pauses the worker and shows coordinates/dimension in its GUI. Use the **Mining Area Wand**: left-click corner A, right-click corner B for an inclusive 3D box, or **Shift-right-click B** for a horizontal footprint extending from the higher of the two selected Y values down through **Y:0**. Reversed corners normalize automatically. Clicks do not mine/place blocks. The held wand previews the boundary and selected blocks; feedback/tooltips show corners and volume. Return to the Miner, choose **Assign selected area**, then **Confirm replacement**, then **Start**. Until confirmed, selection leaves the previous job running unchanged. Assignment pauses before replacing it; Start also resumes a paused job. Invalid selections explain their rejection without clamping.

The GUI exposes all existing 27 cargo slots, the actual equipped pickaxe with durability, a dedicated torch supply slot, and player inventory/hotbar with normal and Shift-click transfers. Tools and torches can be supplied directly or through the station; resources can be retrieved. Requests show one missing/suitable-tier pickaxe and enough torches to reach 32. The worker fetches suitable replacements, retains metadata/NBT, and automatically retries supply/full-storage/missing-station waits while Start remains active. It places supported torches near itself when local combined light is below 8, respecting faction ownership and Forge placement cancellation, and consumes supplies only after accepted placement. A blocked lighting position has its own status. Requests update without tick-by-tick chat. Loaded workers remain paused until Start.

The worker still walks to storage and mines real blocks top-down through Forge survival harvesting with a FakePlayer, native hardness and durability. Source tracing confirms this raises BreakEvent into the existing loaded custom stone-drop table. For Miner harvests only, the existing reward handler is deferred until the actual harvest succeeds and the event remains uncanceled; it rolls once, at the mined block's Y, preserving configured chances, quantities, metadata, NBT and Y limits alongside ordinary drops. Cargo collects the resulting real item entities and delivers them to storage. There is no separate Miner loot table or offline production.

Mining requires faction-owned land and continued assigning-member authority. Containers, tile entities, faction infrastructure and liquids remain protected; bedrock/negative-hardness blocks are skipped and advance the cursor. Limits are 64×64 horizontally, world-height depth including Y:0, 262144 total blocks and 128 horizontal blocks from storage to every area corner. Supply walkable access as excavation proceeds: no teleporting, stair construction, liquid draining or chunk loading. Unreachable routes and permission/protection failures stop with a reason. Storage overflow retains cargo and revisits real world drops, subject to normal despawn/pickup. Existing entity IDs, faction/employer, job/cursor, cargo and equipped-tool NBT remain compatible; old configured jobs infer their station assignment. New torch storage has its own NBT field. Changing area is refused while previous overflow drops await collection. Updated client/server packets must match. Lighting, placement cancellation, chest access, movement, inventory/death/reload behavior and visual appearance still require in-game testing.

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
