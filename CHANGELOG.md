# Changelog

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
