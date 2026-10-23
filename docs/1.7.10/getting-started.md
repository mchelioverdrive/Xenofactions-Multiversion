# Getting Started with Xenofactions

This guide explains the basic gameplay loop for a new player on a Xenofactions server.

Open the faction menu (K by default) for **Start here** and **Help**. These pages keep their selected page when revisited. Buttons use Tab/Shift+Tab or arrow keys for selection and Enter/Space for activation; mouse clicks, the wheel and on-screen scroll arrows are also available. Descriptions stay below their actions. Action forms support Tab/Shift+Tab and Enter; Escape leaves a setup form for normal play.

Successful faction creation, through either a command or its action form, starts optional guidance. The server opens the existing **Get a City Center** form, confirms delivery of the named flag, closes the form for world placement, then opens **Set Faction Home** only after accepted placement establishes a city. The small instruction panel stays at the top left during play; narrow action forms reserve space above their content. Successful home creation shows a brief completion message and clears the panel.

**Resume faction setup** and **Dismiss guidance / Show guidance** are available in Start here and the Help topic list. Closing a form keeps the confirmed step. Dismissal is saved and never prevents normal play; reopening resumes the saved step without repeating delivery. Existing faction membership alone does not start guidance. Failed commands keep the form and its arguments, with the server's reason; resolve that reason and retry. During placement, a rejection keeps the flag and the placement instruction. Check the server's chat message for foundation, territory, dimension or protection restrictions.

The initial delivery step and inventory are committed together in the existing UUID player snapshot. Repeated claims, reopened forms and reconnects cannot issue another setup flag while its placement is outstanding, including with survival recipes enabled. Find that flag in your inventory, storage or dropped items before trying to place it; the guide does not replace lost flags. Costs, rank checks, first-city capital designation and placement rules still come from the existing server handlers. No tutorial completion is required to play. Active visible setup and its completion message take priority over automatic daily-wheel openings; queued daily rewards remain available after completion or dismissal, and `/xdaily` still opens them manually.

Guide state is the optional `FactionSetup` compound (`Faction` UUID, confirmed `Step`, and `Hidden`) under `ForgeData.PlayerPersisted.xfDailyRewards`. It uses the existing player save/load/clone path and atomic player-file replacement. Client intents only show, dismiss or resume; they cannot set progress or grant items. Accepted city/home world changes retain Minecraft's existing world-save behavior; the player and world files are not one crash-atomic transaction. Updated clients and servers must match for the extended onboarding snapshot. In-game layout at different scales, cancellation, reconnect/restart, full inventories, storage failures and dedicated-server operation still require runtime verification.

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
