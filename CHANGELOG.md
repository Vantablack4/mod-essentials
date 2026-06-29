# Changelog

## 0.3.0

- Added another EssentialsX parity pass over command behavior and messages:
  player-qualified `/home player:name` and `/delhome player:name`, paged
  `/warp`/`/warps`, persistent `/tptoggle` and `/tpauto`, richer `/tppos`
  yaw/pitch/dimension syntax, clear-inventory wildcard/item/amount filters,
  and optional dimension syntax for weather aliases.
- Expanded economy, mail/social, admin/offline, item, player, and world command
  compatibility, including `/balancetop force`, wildcard `/eco`, timed mail,
  `/tpoffline`, `/banip` remembered-player IP targeting, `/book` sign/unsign,
  `/itemname` no-arg clear, `/ptime` and `/pweather` list/show forms,
  `/condense <item>`, `/unlimited ... [player]`, richer `/fireball`,
  upstream-style `/nuke`, `/editsign copy|paste`, and broader `/remove`
  categories.
- Bundled the remaining upstream EssentialsX default resources under
  `src/main/resources/essentialsx`: MOTD, rules, info, custom text, book,
  kits, TPR, worth, item database, custom items, and release marker.
- Updated parity tooling to separate syntax-only Brigadier usage differences
  from remaining behavior/platform gaps.

## 0.2.0

- Expanded the Fabric command surface to cover all upstream EssentialsX command
  roots from the retained `plugin.yml` files.
- Exposed all upstream aliases tracked by `scripts/essentialsx-parity.mjs`.
- Added parity tracking:
  - `docs/fabric-command-manifest.json`
  - `docs/essentialsx-parity.md`
  - `scripts/essentialsx-parity.mjs`
- Added/expanded command families:
  - direct/request teleport, homes, warps, spawn, `/back`
  - player state, gamemode, inventory, item/meta, GUI utility commands
  - economy, worth, sell, pay, and admin economy mutations
  - private messages, mail, AFK, nick, social toggles, helpop, socialspy
  - bans, temp bans, IP bans, kicks, mutes
  - jails, random teleport, kits, exp, ptime, pweather, powertools
  - world/fun/entity commands including firework, spawner, editsign, vanish
  - static text commands and explicit Discord/XMPP compatibility roots
- Retained upstream EssentialsX `config.yml` and message bundle resources under
  `src/main/resources/essentialsx`.
- Added quoted display/character-name targeting for online-player commands.
- Kept homes, warps, spawn, and `/back` storage compatible with `0.1.x`.

## 0.1.1

- Added character-name targeting documentation for the initial command port.

## 0.1.0

- First server-side Fabric port with spawn, homes, warps, `/back`, TPA,
  heal/feed/fly/god, broadcast, and status/help commands.
