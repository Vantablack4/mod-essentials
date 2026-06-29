# Vantablack Essentials

Server-side Fabric port of the EssentialsX command surface for the Vantablack
Minecraft server.

This repository is a fork of [EssentialsX/Essentials](https://github.com/EssentialsX/Essentials)
and remains GPL-3.0-only. The active build is a Fabric mod rather than the
upstream Bukkit/Paper plugin suite.

## Scope

This fork keeps the upstream EssentialsX source tree for license and porting
reference, while the active runtime is the Fabric mod under `src/main`.
The maintained parity manifest is `docs/fabric-command-manifest.json`; regenerate
`docs/essentialsx-parity.md` with `node scripts/essentialsx-parity.mjs`.

Included command groups:

- Status/help: `/vessentials`, `/essentials`
- Spawn: `/spawn`, `/setspawn`
- Homes: `/home [name]`, `/home <player>:<name>`, `/sethome [name]`,
  `/delhome <name>`, `/delhome <player>:<name>`, `/homes`
- Warps: `/warp [name|page]`, `/warps [page]`, `/setwarp <name>`,
  `/delwarp <name>`
- Return/requests: `/back`, `/tpa`, `/tpask`, `/tpahere`, `/tpaall`,
  `/tpaccept`, `/tpyes`, `/tpdeny`, `/tpno`, `/tpacancel`, `/tpauto`,
  `/tptoggle`
- Admin teleport: `/tp`, `/teleport`, `/tphere`, `/s`, `/tpall`, `/tppos`,
  `/top`, `/jump`, `/bottom`, `/world`
- Player state: `/heal`, `/feed`, `/fly`, `/god`, `/speed`
- Gamemode: `/gamemode`, `/gm`, `/gms`, `/gmc`, `/gma`, `/gmsp`
- Inventory/tools: `/clearinventory`, `/ci`, `/repair [hand|all]`, `/hat`,
  `/enderchest`, `/ec`, `/ext`, `/extinguish`, `/near`, `/invsee`,
  `/disposal`, `/workbench`, `/anvil`, `/cartographytable`, `/grindstone`,
  `/loom`, `/smithingtable`, `/stonecutter`
- Item/meta: `/more`, `/itemdb`, `/give`, `/item`, `/i`, `/enchant`,
  `/itemname`, `/itemlore`, `/book`, `/book title`, `/book author`, `/potion`,
  `/recipe`, `/skull`
- Economy: `/balance`, `/balancetop`, `/pay`, `/paytoggle`,
  `/payconfirmtoggle`, `/eco`, `/worth`, `/setworth`, `/sell`
- Social/player utilities: `/msg`, `/r`, `/ignore`, `/msgtoggle`, `/rtoggle`,
  `/socialspy`, `/helpop`, `/me`, `/afk`, `/mail`, `/nick`,
  `/toggleshout`, `/playtime`, `/seen`, `/whois`, `/realname`, `/exp`,
  `/ptime`, `/pweather`, `/condense`, `/unlimited`, `/powertool`,
  `/powertoollist`, `/powertooltoggle`, `/backup`, `/customtext`
- Admin/sanctions: `/ban`, `/tempban`, `/unban`, `/banip`, `/tempbanip`,
  `/unbanip`, `/kick`, `/kickall`, `/mute`
- Jail/random teleport: `/setjail`, `/deljail`, `/togglejail`, `/jails`,
  `/jailedplayers`, `/tpr`, `/settpr`
- World/fun/entity: `/lightning`, `/antioch`, `/nuke`, `/fireball`,
  `/firework`, `/beezooka`, `/kittycannon`, `/break`, `/ice`, `/tree`,
  `/bigtree`, `/spawnmob`, `/spawner`, `/editsign`, `/remove`, `/vanish`
- World/admin broadcast: `/day`, `/night`, `/noon`, `/midnight`, `/sun`,
  `/storm`, `/thunder`, `/broadcast`, `/bc`, `/alert`, `/broadcastworld`,
  `/burn`, `/rest`, `/list`, `/gc`, `/renamehome`, `/warpinfo`
- Compatibility roots: `/discordbroadcast`, `/discord`, `/link`, `/unlink`,
  `/setxmpp`, `/xmpp`, `/xmppspy`, `/tpoffline`

Player-targeting commands accept the Minecraft account name or the in-game
display/character name. Quote display names that include spaces, for example:

```text
/tpa "Character Name"
/tphere "Character Name"
/gm creative "Character Name"
```

Fabric compatibility boundaries:

- The command roots and upstream aliases from the retained EssentialsX
  `plugin.yml` files are registered. See `docs/essentialsx-parity.md` for exact
  coverage and usage differences.
- Bukkit/Paper-only integrations such as Vault, LuckPerms, EssentialsX Discord,
  DiscordLink, XMPP, Bukkit event hooks, and full display-name packet/list
  behavior need Fabric-specific bridge or mixin modules. Compatibility command
  roots are registered where no bridge exists yet.
- `/tpoffline` teleports to logout locations observed by this Fabric port.
  Generic offline player inventory/NBT mutation is still avoided unless a
  dedicated player-data DataFixer/save bridge is added.
- Upstream message bundles and default EssentialsX resources are retained under
  `src/main/resources/essentialsx`, including `config.yml`, messages, MOTD,
  rules, info, kits, worth, item databases, custom items, book defaults, TPR
  defaults, and the upstream release marker.

## Configuration

The mod writes `config/mod_essentials/config.properties` on first boot:

```properties
commands.admin-permission-level=2
homes.max-per-player=5
teleport.request-timeout-seconds=60
healing.remove-effects-on-heal=true
runtime.fly-and-god-enabled=true
```

State is stored in the same directory:

- `spawn.properties`
- `homes.properties`
- `warps.properties`
- `economy-accounts.properties`
- `economy.properties`
- `worth.properties`
- `kits/`
- `mail.properties`
- `social-state.properties`
- `teleport-state.properties`
- `mutes.properties`
- `logout-locations.properties`
- `jails.properties`
- `jail-state.properties`
- `random-teleport.properties`

Runtime `/back` and pending TPA requests are intentionally in-memory only.

## Build

```bash
./gradlew build
```

The jar is produced under `build/libs/`.

## Serverpack Integration

This mod is server-only and should be registered in `mc-serverpack` as:

```yaml
mod-essentials:
  artifact: mod-essentials
  repository: mod-essentials
  sourcePath: ../mods/mod-essentials
  side: server
```

Production artifacts are published by `Vantablack4/mc-serverpack` through the
internal mod release workflow after this repo has a releasable commit.
