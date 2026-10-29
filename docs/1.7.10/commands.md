# Command Reference

Commands below were verified from source files in `src/main/java/com/hfr/command` and registration in `MainRegistry.serverStarting`.

## Permissions

Minecraft Forge 1.7.10 commands use permission levels when `getRequiredPermissionLevel()` is implemented.

- **Player commands:** permission level 0 or explicit `canCommandSenderUseCommand=true`.
- **Moderator/admin commands:** usually permission level 2 or 3, or vanilla `CommandBase` defaults when no override is present.
- `/xclowder`, `/xplayer` and `/xshop` use permission level 3. Earth raster source, pregeneration, geographic, road and feature administration additionally requires level 4.
- `/invsee` uses level 2. `/xmute`, `/xunmute` and `/stonedrop` inherit vanilla `CommandBase` level 4. TDM administration requires level 4; its player actions use level 0. `/xdebug` uses level 0.


## GUI command list

Level-3 `/xc presence show` lists AFK detection, AFK announcements, idle timeout and the cosmetic operator prefix. `/xc presence set <enableAfkDetection|enableAfkAnnouncements|enableAdminPrefix> <true|false>` and `/xc presence set afkTimeoutSeconds <1..86400>` validate, atomically save to `hfr.cfg` and apply immediately. Save failures preserve previous settings. Console use is supported. Each setting has its own command-menu action; these commands do not change operator status or permissions.

Press the configured Xenofactions menu key (K by default). Search spans all categories; clearing it restores the selected category. `>` beside an argument cycles the handler's literal choices or server-provided values. Text fields remain editable. Longer forms use the `<` / `>` buttons beside **Arguments**; paging retains entered values. Fill earlier optional arguments before later ones to avoid shifting their command positions. The preview shows the exact slash command; **Run Command** and **Open in Chat** use normal server chat-command dispatch. Dangerous actions retain GUI confirmation, and `chatreward reset` also includes the required literal `confirm` token. Server validation, permissions, faction ranks, saving and synchronization still belong to the existing handlers.

The large top arrows change **Category**; the bottom arrows change **Page N of M**. Hover over an arrow for its purpose. Arrows visibly disable at their limits, and neither categories nor pages wrap. Wheel down advances and wheel up goes back: over the category header/arrows it changes category, over the action list or bottom page controls it changes action page, and over an argument form or its page controls it changes argument page. Search, unrelated controls and areas outside these regions leave navigation unchanged. Argument choice buttons retain their existing click-to-cycle behavior. Wheel events stay in the GUI and do not change the hotbar. Action counts, page bounds and displayed rows use the same permission/config/search-filtered list and layout capacity, including a partial final page.

Entries use action names such as **Teleport to Faction Home**, **Set Faction Home**, **Create Faction**, and **Add Daily Reward**. Usage, aliases and the preview retain command syntax; search matches action names, descriptions, canonical command syntax and aliases.

Closing the menu, running a command, opening chat or visiting the tutorial remembers the last category, selected action/detail form, search text, action page and argument page in client memory. Returning from a form to the list remembers the list rather than reopening that form next time. Reopening Faction actions from the main menu requests fresh server data before restoring that location. Category arrows return to that category's saved search and action page in list view. Unavailable actions or insufficient faction rank fall back to the current command list. Search/filter changes, refreshed permissions and resizing retain the current page if valid, otherwise clamp it to the nearest valid page; argument pages also clamp to the current layout. Argument values and confirmations are not remembered across reopening or category changes. The first opening defaults to Faction; connection or player-instance changes clear navigation. No navigation configuration file is written. Start here and Help keep their own selected pages, with compact action groups, scrolling and keyboard selection. Successful faction creation starts the optional server-confirmed setup described in [getting started](getting-started.md).

The catalog covers all currently registered command roots and their distinct actions, including:

- `/xdaily` (`/daily`) and all `/xc daily` help, status, reload, list/show, held-item edits, weight/count, removal, milestones, enablement, settings, timezone and reset actions.
- `/xrewards collect` and `/xc chatreward create`, `set`, `addheld`, `enable`, `disable`, `list`, `show`, `remove` and `reset`. The set form selects the exact field token and preserves spaces in phrases and JSON.
- `/xwallart`; `/c disband`, territory permission inspection/editing, city terrain inspection/retry and the legacy retreat response; explicit `/cc` channel/muting actions.
- `/xc clearcreationcooldown`, zone editor actions, world border actions, leaderboard give/configure, ender chest show/set and Earth source, pregeneration, geographic, road and feature action trees. Road/feature apply still requires the handler's reviewed token and manual-backup acknowledgement.
- Separate `/stonedrop` held-item add, height-range add, list and remove forms; every current `/xshop` action including help. Shop names keep spaces where the handler joins trailing text; use stable IDs in single-token positions.
- TDM player help, maps, voting, skip, menu, team changes, overlay, utility and killstreak list/buy; level-4 map/settings/spawns, kit editing and mode/cost controls, selection editor, utility/killstreak definition add/remove, boundary view, match controls and sound/debug actions. Mode voting and map voting have separate forms; score/timer and spawn forms expose optional modes.

Permission filtering uses a server-provided per-entry permission snapshot, distinguishing levels 2, 3 and 4. Faction requirements are displayed, including the server-configured city rename threshold; they remain enforced by the server. Disabled TDM, custom-flag and conquest-flag features are filtered using server menu data. Refresh requests a new snapshot. The city and enemy selectors use current city names, enemy relations and active wars; incomplete lists still allow direct input.

The menu normalizes spaces to underscores for the faction argument of `/c peace`, whose optional transfer city follows that one token. Other phrases, names and messages retain the spacing their handlers support. Minecraft 1.7.10 chat packets truncate at 100 characters; the menu rejects longer commands before sending. Use held-item capture or the server console for long reward JSON. Routine menu commands and supported live administration need no restart. `/xmarket` and the displayed `/xp` alias have no current registered handlers and are omitted; aliases that perform the same action do not get duplicate rows. Menu requests and responses now carry a request ID so delayed replies cannot replace a newer opening's data. Client and server must use the matching menu packet format.

## Factions: `/clowder` and `/c`

Usage: `/clowder help`; aliases: `/clowder`, `/c`; permission level: 0.

### Basic information

| Command | Purpose |
| --- | --- |
| `/c help {page}` | Show in-game help pages. |
| `/c create <name>` | Create a faction. Blocked only while a faction-creation cooldown is active. |
| `/c disband <faction name>` | Leader-only regular disband. Requires the exact faction name as confirmation, warns members, then blocks the leader from creating factions for 7 real days and other current/offline members for 3 real days. Applications and joining remain allowed. |
| `/c info {faction}` | Show your faction or another faction. |
| `/c list` | List factions. |
| `/c comrades` | List faction members. |
| `/c permissions` or `/c perms` | View ally and neutral territory permissions. Officers/leaders may set one with `/c permissions <ally\|neutral> <permission> <true\|false>`. |
| `/c alliance`, `/c allies`, or `/c allylist` | Show allied factions and pending alliance offers. |
| `/c balance` | Show faction prestige/balance. |

### Membership

| Command | Purpose |
| --- | --- |
| `/c apply <faction>` | Apply to join a faction. |
| `/c invite <player>` | Officer or leader: invite an online player outside any faction; equivalent pending invites are rejected. |
| `/c uninvite <player>` | Officer or leader: revoke a pending invitation, including while the invited player is offline. |
| `/c join <faction>` | Accept a pending invitation; applications remain a separate membership path. |
| `/c applicants` | List applications. |
| `/c accept <player>` | Accept an application. |
| `/c deny <player>` | Deny an application. |
| `/c leave` | Leave your faction. |
| `/c kick <player>` | Kick a member. |
| `/c promote <player>` | Promote a member to officer. |
| `/c demote <player>` | Demote an officer. |
| `/c owner <player>` | Transfer ownership. |

Invitations are saved in the existing faction data with faction and player UUIDs, using the configured player identity mode for matching. Faction renames preserve them; use the current faction name to join. Successful joining, application acceptance, faction creation or forced joining clears all your invitations. Disbanding removes the faction’s invitations. Failed joining keeps the invitation. TDM blocks invitation and joining commands.

### Identity and flags

| Command | Purpose |
| --- | --- |
| `/c rename <name>` | Rename your faction. |
| `/c color <hexadecimal>` | Set faction color. |
| `/c motd <message>` | Set faction message of the day. |
| `/c listflags {page}` | List built-in flags. |
| `/c flag <flag>` | Set a built-in faction flag. |
| `/c flag seturl <https URL>` | Import a direct PNG/JPEG image or individual Postimages image page if enabled and allowed; see [custom flag URL policy](configuration.md#custom-flags). |
| `/c flag clear` | Clear custom flag metadata. |
| `/c flag reload` | Reload a cached custom flag image. |

### Claims, cities, and travel

| Command | Purpose |
| --- | --- |
| `/c claim <city name>` | Receive a named City Center item to place and found a city. |
| `/c city upgrade` | Officer or leader: upgrade your existing city where you are standing; repeat within ten seconds to confirm. `/c claim upgrade` remains an alias. |
| `/c city terrain [retry]` | Inspect this node's terrain eligibility; Officer or leader may retry the current owned city's expansion without another upgrade charge. |
| `/c city cancelmove` | Leader-only: cancel the pending relocation without changing the City Center or its claims. |
| `/c city recovermove` | Leader-only: issue a replacement token for the active server-authorized relocation. |
| `/c nameclaim <name>` | Name the current claim. |
| `/c capital` | Show the designated capital and remaining change cooldown; separate from city upgrade level. |
| `/c capital set <owned city>` | Leader-only: change the designated capital. Default cooldown between changes is seven days. |
| `/c sethome` | Officer or leader: set faction home inside the designated capital only. |
| `/c home` | Teleport to faction home. |
| `/c setallywarp` | Officer or leader: set your faction's ally rally point inside its claimed land. Shares the ten-minute home-setting cooldown. |
| `/c allywarp <faction>` | Travel to an allied faction's rally point. Faction names take priority; player names resolve through the existing alliance faction lookup. |
| `/c setwarp <name>` or `/c addwarp <name>` | Create a named faction warp. |
| `/c delwarp <name>` | Delete a faction warp. |
| `/c warp <name>` | Use a faction warp. |
| `/c warps` | List faction warps. |
| `/c gracebuild` | Use faction build grace when enabled. |

`/clowder` and `/c` use the same city handler. City actions are explicit: unknown actions (including misspellings such as `city ugrade`) and extra arguments return usage, never a founding item. Use `/c claim <city name>` for founding. The command menu's upgrade action sends the fixed `/clowder city upgrade` command.

Confirmation rechecks membership/rank, the owned City Center and center claim, dimension/location/identity, active foundation, sequential level, configured price and upkeep capacity. Changing the target or quoted settings requires a fresh confirmation. An upgrade retains the city's identifier, name, flag position and dimension. Preparation or commit failure retains its previous level, territory and balances.

Level advancement and territory completion are reported separately. A successful level upgrade pays the configured prestige cost and changes upkeep once; it retains existing city nodes and occupied/protected territory. New wilderness grants reject only when every surface column is open water at least three consecutive water blocks deep (still or flowing). Any exposed solid land, solid platform above water or shallower water column allows the node; submerged seabed, leaves, plants and floating items do not count as exposed land. There is no coverage, connectivity, height-step or biome requirement. The bounded synchronous loaded-terrain check is independent of queued classification, road data and map overlays. The response counts added, terrain-rejected, unavailable and occupied/protected nodes; the radius remains a limit rather than a guaranteed fully claimed area. No deferred ownership grants remain. `/c city terrain` reports the immediate check; `/c city terrain retry` optionally rechecks the saved paid radius after construction, reclamation or loading unavailable terrain without another upgrade charge. Ordinary loaded-land founding resolves its available territory within placement and needs no sampling wait or expansion retry, including after restart. Level, territory metadata and balances retain existing NBT saves and client synchronization.

Ally rally travel waits ten seconds and cancels horizontal movement. The destination faction must list your faction as an ally; a pending alliance offer grants no access. The server rechecks membership, alliance access, bankruptcy restrictions, origin territory, rally coordinates/dimension and dimension availability before teleporting. A renamed faction keeps its pending destination identity; a removed faction or changed rally point aborts with a specific error. Rally travel does not grant access to that faction's named `/c warp` destinations. Existing Earth destination safety and border handling remain in effect.

### Economy

| Command | Purpose |
| --- | --- |
| `/c deposit <amount>` | Deposit prestige/resources into the faction system. |

### Diplomacy and war

| Command | Purpose |
| --- | --- |
| `/c ally <faction>` or `/c befriend <faction|player>` | Request an alliance. |
| `/c acceptally <faction|player>` or `/c acceptfriend <faction|player>` | Accept an alliance request. |
| `/c unally <faction>` or `/c unfriend <faction>` | Break an alliance. |
| `/c enemy <faction>` | Leader-only one-way enemy marker. It is separate from wars, costs no Prestige, and enemy kills grant faction Prestige. |
| `/c unenemy <faction>` | Leader-only enemy removal request. The enemy relation remains active for 72 real hours before expiring. |
| `/c declarewar <faction>` | Declare war when war mode/rules allow. |
| `/c peace <faction> {city}` | Request peace, optionally including a city transfer. |
| `/c acceptpeace <faction>` | Accept peace. |
| `/c ceasefire <faction>` | Request a ceasefire. |
| `/c acceptceasefire <faction>` | Accept a ceasefire. |
| `/c surrender <faction>` | Offer surrender. |
| `/c acceptsurrender <faction>` | Accept surrender. |
| `/c defendally <ally>` | Join an ally's defense when enabled. |
| `/c retreat` | Run the legacy response; the current handler does not start retreat or automatic kicking. |

## Faction administration: `/xclowder` and `/xc`

Usage: `/xclowder help`; aliases: `/xclowder`, `/xc`; permission level: 3.

| Command | Purpose |
| --- | --- |
| `/xc enderchest [show [setting]]` | View all six global ender chest settings, or one named setting, with effective/configured values and enabled state. Console supported. |
| `/xc enderchest set <setting> <value...\|clear>` | Validate, save and immediately apply a global ender chest setting. Lists are replaced; `clear` empties a list. Console supported. See [exact syntax, values, persistence and open-container behavior](configuration.md#ender-chest-restrictions). |
| `/xc forcejoin <faction>` or `/xc fj <faction>` | Force the sender into a faction. |
| `/xc forcekick <player>` or `/xc fk <player>` | Force-kick a player from their faction. |
| `/xc forcedisband <faction>` or `/xc fd <faction>` | Force-disband a faction. |
| `/xc forcerename <name>` or `/xc fr <name>` | Force-rename the sender's faction. |
| `/xc hijack` | Override faction leadership. |
| `/xc deletedata` | Delete all clowder/faction data. Use with extreme caution. |
| `/xc disband <faction>` | Admin disband path with confirmation behavior. |
| `/xc rename <name>` | Admin rename path. |
| `/xc setclaim <wild/safe/war> <s/c> <radius>` or `/xc sc ...` | Set wilderness/safezone/warzone claims in square (`s`) or circle (`c`) radius. |
| `/xc addprestige <faction> <amount>` or `/xc ap ...` | Add or subtract prestige. |
| `/xc newplayerprotection` | Toggle starter protection if the module is enabled. |
| `/xc resetnewplayerprotection` | Reset starter protection timers. |
| `/xc endnewplayerprotection` | End starter protection timers. |
| `/xc resetbuildgrace <faction>` or `/xc rbg <faction>` | Reset faction build grace. |
| `/xc endbuildgrace <faction>` or `/xc ebg <faction>` | End faction build grace. |
| `/xc clearcreationcooldown <player-or-uuid>` or `/xc resetcreationcooldown ...` | Clear one online or offline player creation cooldown. UUIDs, live/cached profiles, and stored names are supported; ambiguity fails closed. |
| `/xc warenable` | Enable war declarations. |
| `/xc wardisable` | Disable war declarations and clear active wars. |
| `/xc skipwarcooldowns` | Toggle global war cooldown bypass. |
| `/xc skipwarcooldown` | Clear all faction war cooldown timers. |
| `/xc ignorewarcooldowncheck` | Toggle cooldown check bypass. |
| `/xc ignorewaronlinecheck` | Toggle online-member check bypass. |
| `/xc ignorewarstatecheck` | Toggle at-war state check bypass. |
| `/xc enablelegacywar` | Toggle legacy war mode, bypassing modern checks. |

The `/xc factiontimeoutcreationreset <playername>` command also requires permission level 3. It resets only that player's faction creation cooldown, accepts online players and offline players known to the server profile cache or cooldown store, and can be run by an admin player or the server console.

## Faction chat: `/cc`

Usage: `/cc [public|faction|alliance|mute|unmute]`; permission level: 0. Send messages normally after choosing a channel.

| Command | Purpose |
| --- | --- |
| `/cc` | Cycle public, faction and alliance channels. |
| `/cc f` or `/cc faction` | Use faction channel. |
| `/cc a` or `/cc alliance` | Use alliance channel. |
| `/cc p` or `/cc public` | Return to public chat. |
| `/cc m` or `/cc mute` | Mute public chat. |
| `/cc u` or `/cc unmute` | Unmute public chat. |

## Persistent command data

`clowder_faction_creation_cooldowns.json` stores UUID-keyed faction creation cooldown expirations plus last-known player names and fallback name entries for unresolved offline members. Expired entries are ignored/cleaned on load or lookup.

## Miner diagnostics: `/xminerdebug`

Requires operator permission level 3. `<id>` accepts the existing miner UUID (tab completion) or its current entity ID; the miner must be loaded in the sender's dimension. Output includes both identifiers. This is separate from the public `/xdebug` command.

| Command | Output/action |
| --- | --- |
| `/xminerdebug <id> on` | Enable a transient history of up to 200 meaningful decisions. |
| `/xminerdebug <id> off` | Disable tracing and clear the history. |
| `/xminerdebug <id> report` | State, retained objective, supplies, construction/recovery failures and history. |
| `/xminerdebug <id> movement` | Targets, completions, reversals since useful work and the decisions that selected movement. |
| `/xminerdebug <id> inspect <x> <y> <z>` | Read a loaded block and recorded access requirements; no chunk loading, simulation or edits. |

Tracing is disabled by default and not persisted. Enable it before reproducing; reports without tracing still show current diagnostic fields. Endpoint failures separate footing, body clearance and ladder attachment. With tracing enabled, each exhausted endpoint search records at most eight nearby rejected candidates, including coordinates, movement type, failed predicate, collision bounds or sight-ray hit, required footing/attachment, interaction target/reach and any direct unfinished construction prerequisite. These use the same candidate predicates as planning; a rejected endpoint is not a failed movement attempt. Changes internal to protection mods may need the miner's existing Start/Resume action after the permission is corrected.

Movement reports include the task, original storage reason, selected depot, recovery phase, actual feet position, retained movement rejection and next repair/prerequisite. Initial connections and graph edges report their failed rise, drop, clearance, footing or landing predicate; clearance/landing details identify the sampled body position. A retained execution rejection is reported separately from the edge's current geometry. Recovery failures remain alongside the original navigation failure in the GUI details. Zero required supports or ladders do not indicate a material shortage. Overflow capacity is recalculated for an actual buffered stack, including metadata and NBT compatibility.

## Utility and moderation commands

| Command | Permission | Purpose |
| --- | --- | --- |
| `/xignore <player>` | Player | Toggle ignoring a player. |
| `/xmute <player> <seconds\|perm> [reason]` | Admin/moderator default | Mute a player. |
| `/xunmute <player>` | Admin/moderator default | Unmute a player. |
| `/invsee <player> [armorslots]` | Level 2 | View another player's inventory or armor slots. |
| `/xmap` | Player | Give yourself a claim map item. |
| `/xflags` | Player | Give yourself conquest flags for use against active war enemies. |
| `/xmulti` | Player | Give yourself a multitool. |
| `/xdebug <player>` | Player | Show a faction member's stored logout timestamp in raw and formatted forms. |
| `/c stonedrops [page]` | Player | Read-only list of custom stone drops, including chance, raw chance, item registry name, metadata, stack amount, and Y range. All players and the server console can use this command. |
| `/stonedrop <rarity> [minY] [maxY]` | Admin/moderator default | Administrator command to add held item/block as a custom stone drop. |
| `/stonedrop list` | Admin/moderator default | Administrator command to list configured custom stone drops. |
| `/stonedrop remove <index>` | Admin/moderator default | Administrator command to remove a custom stone drop. |
| `/xshop add <shop>` | Level 3 | Add a shop offer using hotbar slots: slot 1 sold item, next three slots currency. |
| `/xshop create <name>` | Level 3 | Create an empty shop with a permanent ID. |
| `/xshop delete <shop>` | Level 3 | Delete the shop; linked blocks become inactive. |
| `/xshop rename <ID/name> <new name>` | Level 3 | Change the display name without breaking block links. |
| `/xshop list [page]` | Level 3 | List shop names, IDs, offers, and eligibility settings. |
| `/xshop edit <shop>` | Level 3 | Open the settings and offer editor. |
| `/xshop set <ID/name> <enabled\|visible\|adminOnly> <on\|off>` | Level 3 | Control shop access and faction-market availability. |
| `/xshop link <ID/name> <x> <y> <z>` | Level 3 | Link a loaded admin shop block in your dimension. |
| `/xshop unlink <x> <y> <z>` | Level 3 | Unlink a loaded admin shop block in your dimension. |
| `/xshop removeoffer <ID/name> <index>` | Level 3 | Remove a zero-based offer. Legacy `/xshop delete <index> <shop>` also remains supported. |
| `/xplayer <cbt\|ramranch\|fps\|tilt\|shader\|vomit> <player>` | Level 3 | Toggle low-FPS, screen-tilt, or rapidly alternating shader effects for a player. |

XShop commands also have the `/shop` alias. Use a stable ID from `/xshop list` for names containing spaces in commands with additional arguments. Creation, deletion, editing, and adding offers accept a complete name as their trailing argument. Tab completion offers configured IDs and single-word names, settings, boolean values, offer indices, list pages, and block coordinates. Catalog-management commands work from the console; GUI, hotbar, and block-link operations require a player.

## Optional TDM: `/tdm`

Registered only when `enableTDM=true`. `/tdm help [category]` provides paged, permission-aware help. Player pages are `general`, `match`, and `teams`; operators additionally see `kits`, `maps`, and `admin`. Tab completion is server-authoritative for online players and configured maps, and covers command names, subcommands, teams, modes, map settings, and other fixed values.

Player commands are `/tdm menu`, `/tdm maps`, `/tdm overlay <on|off>`, `/tdm vote <map> <tdm|sd|ffa>`, `/tdm skip [yes|no|status]`, and `/tdm teamchange`. The vote GUI shows each enabled map and mode pairing separately; an omitted vote mode uses that map's default for compatibility. Setup and match-control commands remain operator-only and are not suggested to ordinary players. Use the categorized help pages for exact required/optional arguments and examples.

The selected map's enabled border appears automatically for all players in its dimension, including before a match starts, on JourneyMap or on the standalone HUD map when JourneyMap is unavailable. Search and Destroy sites appear while that pairing is active in a running match. `/tdm overlay` controls extra permitted map details and does not hide those public shapes; the editor no longer has a Map overlay button. Right Alt toggles in-world border effects and the standalone fallback map, but never JourneyMap shapes; its key can be changed in Controls.

Administrators can open `/tdm editor gui` or press F10 (configurable in Controls) for the draggable map, kit, spawn, area, and settings panel. Reopening restores that administrator's last editing context independently of the active match; `/tdm editor gui <map> <team> <kit>` selects a specific target. The Maps page enables voting modes separately. The Spawns page edits one mode's spawn set at a time. The Kits page previews the selected direct kit, can create from the current inventory, clone, rename, set Search and Destroy buy score, restrict modes, or load it into creative inventory for natural slot editing. After loading, use `/tdm kit commit` or `/tdm kit cancel` (also available in the panel). The original inventory is restored in both cases. Use the Map/Global control to inspect global fallback kits; a map kit edit never silently changes its fallback. Existing `/tdm kit add`, `list`, and `remove` commands still work.

The Areas page starts a typed selection and gives an Admin Selection Wand when there is inventory room. Close the panel with `Use wand`, left-click a block for A, right-click a block for B, then reopen with F10 to inspect or commit. Air clicks do not set a point. `/tdm editor select <map|bomb_a|bomb_b> [map]`, `/tdm editor point <a|b>`, `/tdm editor commit`, and `/tdm editor cancel` are chat equivalents. Map bounds do not create a claim. `/tdm map border <map> <on|off>` optionally keeps active match players inside their horizontal footprint; it is off by default. `/xc editor select <safezone|warzone|wilderness|border_exempt>` uses the same point/commit/cancel flow for Clowder zones and border exemptions. The preview shows nearby safe and war chunks separately from map bounds and Search and Destroy sites. `/xc setclaim` remains available for the radius workflow.

Map spawn commands are `/tdm map addspawn <map> <red|blue|ffa> [mode]`, `/tdm map updatespawn <map> <number> [mode]`, `/tdm map tpspawn <map> <number> [mode]`, `/tdm map removespawn <map> <number> [mode]`, and `/tdm map clearspawns <map> [mode]`. An omitted mode uses the map default. FFA accepts neutral spawns; TDM and Search and Destroy accept team spawns. Adding or updating a map spawn records the administrator's position and facing; older spawns keep their previous facing behavior. `/tdm map clearbounds <map>` removes optional map preview bounds.

Deathmatch and FFA are continuous modes: login and respawn place a player immediately at a mode-appropriate spawn and open a protected respawn-loadout selector. That selector ignores kit prices, buy score, survivor kits, and Search and Destroy buy timing. FFA loadouts may be selected from either configured RED or BLUE kit pool, but FFA does not assign a team. Competitive Search and Destroy alone owns economy and survivor-kit state; active hardcore Search and Destroy late joiners retain the established round-waiting behavior.

`/tdm skip [yes|no|status]` starts or participates in a vote to skip the current map. Player messages identify the purpose of the vote, show the YES threshold, and announce whether the map will be skipped, the vote failed, or it expired.

Map and mode spawn sets are authoritative, including their configured dimensions. An empty set does not borrow another mode's spawns unless `/tdm map spawnfallback <map> <mode> <source-mode|none>` explicitly configures that sharing. Legacy global spawns remain a fallback only for a map's original default mode. A match with missing required spawns is refused and logged rather than falling through to vanilla worldspawn. Mode changes stop the old lifecycle and clear pending kit, protection, waiting, elimination, objective, and survivor state before starting the new mode.

## Registered-but-not-currently-registered commands

The source contains `CommandOrewand`, `CommandXCum`, and `CommandXCustomImage`, but `MainRegistry.serverStarting` does not register them in the current code path. They are intentionally not documented as usable commands.
### TDM bomb maps

TDM always stores players on the **RED** or **BLUE** team. A bomb map adds objective roles without changing membership: `/tdm map terroristteam <map> <red|blue>` chooses which stored team is Terrorist and the other team is Counter-Terrorist. Sides do not swap automatically.

Use `/tdm map mode <map> <tdm|sd|ffa>` to set the default mode, `/tdm map voteable <map> <on|off>` to include or exclude every mode of a map from votes, `/tdm map voteable <map> <tdm|sd|ffa> <on|off>` to configure individual mode offerings, and `/tdm map select <map> [mode]` to start a specific pairing. Newly created maps begin with map voting off; existing saved maps retain voting. Internally, Search and Destroy keeps the save ID `BOMB`; `sd` is its command alias. `/tdm map hardcorerespawns <map> <true|false>` applies to Search and Destroy: `true` uses competitive elimination and the timed buy phase, while `false` permits respawns and opens protected competitive kit selection after each respawn without a buy timer. TDM and FFA remain continuous. Configure A or B as normalized cuboids with `/tdm map bombsite <map> <a|b> pos1`, `pos2`, or `clear`; positions use the executing player's floored block coordinate and both corners must share a dimension. The two corner commands use the shared typed selection and save together when both are set; an existing site remains active until then.

Use `/tdm kit mode <red|blue> <number> <map|global> <tdm|sd|ffa> <on|off>` to restrict one saved kit without copying it. Old kits default to every mode. The saved kit cost applies only in Search and Destroy; TDM and FFA ignore it. On legacy save migration, neutral map spawns enter the FFA set and team spawns enter the former team mode. Ambiguous team spawns from an FFA map remain preserved: inspect them with `/tdm map legacyspawns <map>` and assign each with `/tdm map assignlegacyspawn <map> <number> <tdm|sd>`.

`/tdm map lossscore <map> <amount>` configures the end-of-round losing-side bonus, while `/tdm map plantscore <map> <amount>` configures the individual successful-plant reward; both default to **1** and accept zero to disable the reward. Map listings show loss, kill, win, plant, and defuse economy values.

`/tdm map scorelimit` and `/tdm map timer` retain their deathmatch meanings on deathmatch maps. On bomb maps they configure wins required (default **13**) and the pre-plant round timer (default **120 seconds**) using separate persisted overrides. `/tdm maps` shows roles, hardcore behavior, effective defaults, and bombsite readiness.

HBM remains optional for Xenofactions and ordinary deathmatch. Playing a Search and Destroy pairing requires HBM's exact runtime block registration `hbm:tile.charge_c4csgo` (with legacy `hbm:charge_c4csgo` fallback); no HBM classes are compile-time dependencies. After buy-time kit setup, each live round gives one CSGO charge to a random active player on the map's configured Terrorist team. The narrow PR #195 Warzone exception remains in effect outside controlled bomb rounds.

### Earth boundary (`/xc worldborder`)

| Command | Effect |
| --- | --- |
| `/xc worldborder on` | Persistently enable the existing Earth boundary. |
| `/xc worldborder off` | Persistently disable the existing Earth boundary. |
| `/xc worldborder status` | Show effective state, geometry, safety margin, and exemption count. |
| `/xc worldborder wand` | Start an exemption selection when idle and give the shared Admin Selection Wand if needed (left-click A; right-click B). |
| `/xc worldborder exempt` | Persist the selected inclusive, all-Y rectangle. |
| `/xc worldborder clearexemptions` | Remove every saved exemption without changing border state or geometry. |

All commands require current `/xc` operator authorization. The console may use on, off, status, and clearexemptions; wand and exempt require an in-game administrator. Create an exemption with `/xc worldborder wand`, left-click position 1, right-click position 2, then `/xc worldborder exempt`. Exemptions persist automatically and bypass only Earth boundary enforcement in dimension 0. Selected blocks cover their full footprint, including the final block column. Boundary decisions use entity footprints and observed movement intersections before computing a wrap destination. A selected section touching a boundary stays open outward perpendicular to that boundary, within the selected span along its edge; this prevents a fast entity from being wrapped after overshooting a thin selection. Players leaving that span outside the map are returned safely to the configured Earth map center; ordinary non-exempt crossings retain the existing wrap behavior. For a world without runtime saved data, the configured `earthBoundaryEnabled` value is used.
# TDM observer administration

`/tdm teamless` is an operator-level command which removes the executing player
from both competitive teams. Use `/tdm setteam <player> <red|blue>` to return the
observer to play.

### FFA and economy map settings

FFA uses only neutral spawns from the selected map's FFA spawn set. It has no active RED or BLUE teams: automatic and manual team balancing do nothing, `/tdm setteam` is unavailable, and team lookups return no team while FFA is selected. Prior team assignments remain saved for a later team mode.

- `/tdm map mode <map> <deathmatch|bomb|ffa>`
- `/tdm map addspawn <map> <red|blue|ffa>`
- `/tdm map lossscore <map> <amount>`
- `/tdm map killscore <map> <amount>`
- `/tdm map roundwinscore <map> <amount>`
- `/tdm map plantscore <map> <amount>`

Reward amounts must be non-negative; zero disables that reward. FFA competitors are not assigned RED or BLUE, and `/tdm teamless` continues to designate an observer rather than a competitor.
# TDM sound diagnostics

`/tdm testsound <ctwin|twin|ctstart|tstart|bombplant>` is operator-only. It selects from the live configured variants and uses the same explicit per-player packet dispatcher as gameplay. Round-start tests play only for the executing operator; victory and bomb-plant tests target the eligible TDM players in the operator's current world.

## TDM score and purchase commands

TDM keeps three unrelated score domains: Search and Destroy **buy score** is money, optional **kill score** buys killstreak rewards, and non-spendable **point score** decides DEATHMATCH/FFA victory. Normal DEATHMATCH/FFA kits remain economy-free.

Player commands:
- `/tdm menu` opens the mode-aware scoreboard/loadout menu. Search and Destroy buy time exposes Utility; configured DEATHMATCH/FFA maps expose Killstreaks and the player's kill-score balance.
- `/tdm utility list` and `/tdm utility buy <number>` list or buy Search and Destroy utility with buy score.
- `/tdm killstreak list` and `/tdm killstreak buy <number>` list or queue a respawn reward using kill score.

Administrator commands:
- `/tdm utility <list|add|remove> [map|global] [cost|number]` captures additive utility definitions from the administrator's inventory.
- `/tdm killstreak <list|add|remove> [map|global] [cost|number]` captures queued killstreak definitions from the administrator's inventory.
- `/tdm map pointlimit <map> <points|default>` sets the non-Search-and-Destroy victory threshold (`scorelimit` remains an alias).
- `/tdm map killstreaks <map> <true|false>` explicitly enables killstreaks on DEATHMATCH/FFA maps.
- `/tdm map killscorereward <map> <amount>` sets spendable kill score awarded per valid kill.

`/tdm help` is task-oriented and permission-aware. Player pages cover Match & Voting, Teams, Kits & Loadouts, and Maps. Operators additionally see administration pages, including `[Admin] /tdm teamless`, Kits, Utility & Killstreaks, and Map Configuration.

Alliance targets resolve a faction name first, otherwise a player and their current faction. Pending offers belong to the sending faction UUID; resolvable historical faction/player keys remain accepted. Only the matching incoming offer is removed after both alliance records are saved.

## Daily rewards (Forge 1.7.10)

See also [chat reward phrases](#chat-reward-phrases-forge-1710) for administrator-configured rewards triggered by private phrase entry.

`/xdaily` (alias `/daily`) opens the custom daily wheel for all players, without operator status. On login, after saved player data loads and the client leaves the terrain loading screen, the server opens this same wheel once if the enabled wheel is available and the current daily reward remains unclaimed under its existing date/reset rules. Already claimed rewards and unfinished or awaiting-delivery spins do not trigger automatic opening; earned-spin balances do not trigger it. Repeated readiness messages, login callbacks and dimension changes do not reopen the wheel during that login, and manually opening it first consumes that login's automatic opening. Closing without spinning leaves `/xdaily` available; eligibility restored by the normal reset rules is checked again on the next login. Active visible faction setup and its short completion message defer automatic wheel display without discarding the pending opening. Completing or dismissing guidance releases it; manual `/xdaily` remains available. Administrative actions remain restricted to level-3 `/xc daily` commands.

**Spin daily wheel** accepts one server-selected item reward per UUID per server calendar date. **Collect saved items** retries pending item delivery after making inventory space. Opening the menu does not accept or deliver a new reward. Closing the GUI leaves unclaimed eligibility available and preserves accepted results. Consecutive claim dates advance the streak; missing a date restarts the next claim at one. Streak milestone bonuses are granted once per day-number milestone per UUID. See [daily reward configuration and persistence](configuration.md#daily-reward-wheel).

Daily spins multiply only the selected wheel reward's configured base count by the streak reached by that claim (minimum x1). A base count of 8 grants 8 on day 1, 16 on day 2, 24 on day 3 and 56 on day 7, plus the unchanged milestone bonus if earned. The next multiplier is shown before spinning; hover wheel items for base counts and totals. The stopped result shows the saved multiplier and total. The server freezes those amounts at acceptance, so edits, resets, reconnects and restarts cannot change them. Legacy unfinished spins and pending items keep their original amounts. Large rewards remain compactly saved and Collect delivers only what fits per attempt; no extra reveal sounds are added.

**Earned spin** spends one separately saved earned spin on this same wheel at base quantities (x1), without changing daily eligibility, streaks, the next daily multiplier or milestone receipts. The button shows your balance. The original 17 achievements on the Forge Xenofactions page automatically credit one spin once, including saved Grain Mill/Fishing Net completions; the 1,000-player-kill achievement (`kill1000`) grants no reward. Page descriptions show server progress, targets and credit status where applicable. See [achievement tracking, distance configuration and persistence](configuration.md#achievements-and-earned-wheel-spins). Both spin types require an enabled populated wheel and collection of earlier pending items, and share the existing animation, sounds and saved delivery path.

The new reward and streak bonus become collectible only after the saved server deadline and the displayed wheel's stop. Automatic delivery follows the final result display, then that daily screen closes after 1.5 seconds. Collect can still deliver unrelated ready items during animation. Closing or disconnecting retains the accepted claim and deadline; reconnect/restart resumes it without rerolling or regranting completed rewards. An unfinished spin must complete before another spin, including after an admin reset.

The wheel plays a local vanilla XP orb pickup sound (`random.orb`) when the item under its marker changes during animation, following the displayed spin speed without replaying missed transitions after a render stall. A single vanilla level-up sound (`random.levelup`) accompanies the stopped spin's first reward reveal, including any streak bonus. Completed results stay silent when reopened or refreshed. These sounds respect Minecraft volume settings; closing the screen stops new wheel sounds and lets an already playing reveal sound finish naturally.

Administrators use the existing level-3 `/xc` namespace (`/xclowder` alias). `/xc daily`, `/xc daily help` and `/xc help 2` expose the commands; completion is permission checked. Console senders can use every command except held-item capture.

| Admin command | Effect |
| --- | --- |
| `/xc daily status` | Show definition availability, managed/import paths, latest load failure and the original exception retained for this server session. Available even when definitions fail to load. |
| `/xc daily reload` | Retry loading without a restart, rereading daily import inputs only if managed data is absent. Preserve the previous valid table and saved files on failure. |
| `/xc daily reset <player>` | Reset an online player's claim restriction by UUID for debugging; preserve streak progress, earned milestone receipts, pending items and delivered inventory. |
| `/xc daily list` | List identifiers, item names/registry IDs/metadata, counts, weights and calculated percentage chances. |
| `/xc daily show <id>` | Inspect a reward and its milestone references. |
| `/xc daily addheld <id> <weight> [count]` | Add a copy of the player's held item with a new stable ID. |
| `/xc daily setheld <id> [count]` | Replace the item/count under an existing ID, preserving its weight and references. |
| `/xc daily setweight <id> <weight>` | Change the weight (1–1000000). |
| `/xc daily setcount <id> <count>` | Change the count (1–4096). |
| `/xc daily remove <id>` | Remove an unreferenced reward; list blocking milestone references otherwise. |
| `/xc daily milestone list` | List milestone day numbers and reward IDs. |
| `/xc daily milestone set <days> <rewardId>` | Add or replace a positive-day milestone. |
| `/xc daily milestone remove <days>` | Remove a milestone definition, retaining players' earned receipts. |
| `/xc daily enable` | Enable new spins when the wheel contains rewards. |
| `/xc daily disable` | Disable new spins while allowing saved item collection. |
| `/xc daily settings` | Inspect configured enablement, actual spin availability, timezone and table sizes. |
| `/xc daily timezone <zone>` | Change the IANA calendar timezone, e.g. `America/New_York`. |

Held-item commands preserve metadata, binary NBT, names and enchantments without consuming or changing the held stack. Omitted counts use its current stack count. Identifiers are administrator-chosen names containing 1–32 letters, digits, underscores or hyphens. For example, while holding diamonds, `/xc daily addheld diamond_reward 10` creates a new reward; `/xc daily setheld diamond_reward` replaces that reward's item while preserving its identifier, weight and milestone references. Weights are relative chances, not percentages: weights 10 and 30 give 25% and 75%. Successful edits validate the complete proposed table, atomically save it and refresh open GUIs immediately; no reload or restart is required. Failed validation or saves keep previous definitions. An empty wheel blocks new spins with an explanation and still permits collection. Remove milestone references before removing their rewards.

The command menu includes `/xdaily` (alias `/daily`) and individual level-3 entries for every daily admin action, including status, reload and reset. Destructive menu actions retain confirmation; all actions dispatch through the ordinary server commands. Unknown actions and incomplete syntax are rejected before reading definitions or resolving reward IDs. Listing and settings need neither a held item nor claim eligibility, and remain usable for empty or disabled tables.

For loading failures, start with `/xc daily status`: initialization, saved-data reading/validation, import validation, missing items and filesystem failures are identified separately. Correct the reported source or permissions, then `/xc daily reload`. Existing unreadable managed data is never replaced by defaults. A failed reload retains the previous valid table for reads and spins under its existing settings, but protects it from edits until loading succeeds. Successful edits need no reload.

`/xc daily reset <player>` accepts an online name, not a selector or offline fallback. It durably records a one-use claim override without deleting the last claim, streak, receipts, wheel/result or pending items. The next debug spin retains the stored streak value instead of resetting or increasing it; subsequent normal dates use normal streak rules. All pending items must still be collected first, and the wheel must be available, enabled and nonempty. Reset invalidates stale spin requests, refreshes an open daily GUI and logs the administrator and target UUID. Save failures retain the previous state. This command does not restore default rewards or change other players.

Edits affect future accepted spins only. Queued requests using an older GUI revision are rejected; accepted results, saved wheel snapshots, pending items, claim dates, streaks and milestone receipts are retained. A timezone edit cannot bypass the accepted claim's original next-midnight guard; legacy offline claims use the timezone retained at import. Moving the calendar backward may delay eligibility. See the configuration guide for one-time import and storage details.

## Chat reward phrases (Forge 1.7.10)

All management commands require the existing level-3 `/xc` authorization; `/xclowder` remains an alias. Console administration is supported except `addheld`. Players redeem by entering an exact configured phrase in chat, ignoring case and edge whitespace. Matched messages are private, including rejected redemptions. Ordinary players have no phrase list, reward inspection or phrase-management command. `/xrewards collect` (level 0) collects that player's saved items across all phrases.

| Admin command | Effect |
| --- | --- |
| `/xc chatreward create <id> <phrase...>` | Create a disabled draft. Spaces in the phrase need no quotes. |
| `/xc chatreward set <id> phrase <text...>` | Change spelling while retaining the ID and all history. |
| `/xc chatreward addheld <id>` | Append the server-owned held stack, including its count, metadata and NBT. Does not consume it. |
| `/xc chatreward set <id> rewards <JSON array>` | Replace all reward entries after validation. Same array format as the configuration file. |
| `/xc chatreward set <id> repeatable <true|false>` | Default false: one successful redemption per UUID. |
| `/xc chatreward set <id> cooldownSeconds <seconds>` | Repeat cooldown; default 60, minimum 1. |
| `/xc chatreward set <id> startsAt <ISO instant|clear>` | Optional inclusive start, e.g. `2026-12-01T00:00:00Z`. |
| `/xc chatreward set <id> expiresAt <ISO instant|clear>` | Optional exclusive expiry. |
| `/xc chatreward set <id> totalLimit <-1|count>` | Total accepted rewards across UUIDs; -1 unlimited, 0 prevents new claims. |
| `/xc chatreward enable <id>` / `disable <id>` | Enable only with valid items; both preserve history and pending items. |
| `/xc chatreward list` / `show <id>` | List definitions or inspect the full configuration and total accepted count. |
| `/xc chatreward remove <id>` | Remove matching/configuration; retain history and pending items. |
| `/xc chatreward reset <id> confirm` | Explicitly clear that ID's total and all UUID counts/cooldowns, including removed IDs. Preserve pending items and receipts. |

Example: `/xc chatreward create welcome Welcome to Xenofactions`, hold the first reward and run `/xc chatreward addheld welcome`, repeat for additional items, then `/xc chatreward enable welcome`. Console example: `/xc chatreward set welcome rewards [{"item":"minecraft:bread","metadata":0,"count":8,"nbt":"{}"}]`. JSON quotes are literal; do not wrap the entire array or phrase in additional quotes. Successful changes are saved immediately to `config/xenofactions-chat-rewards.json`; direct file edits require restart. Reusing a removed ID retains its old history until an explicit reset. See [configuration, limits and delivery persistence](configuration.md#chat-reward-phrases).
