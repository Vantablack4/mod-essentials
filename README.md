# Vantablack Essentials

Server-side Fabric port of the practical EssentialsX command surface for the
Vantablack Minecraft server.

This repository is a fork of [EssentialsX/Essentials](https://github.com/EssentialsX/Essentials)
and remains GPL-3.0-only. The active build is a Fabric mod rather than the
upstream Bukkit/Paper plugin suite.

## Scope

Included in the first Fabric port:

- `/spawn`, `/setspawn`
- `/home`, `/sethome`, `/delhome`, `/homes`
- `/warp`, `/warps`, `/setwarp`, `/delwarp`
- `/back`
- `/tpa "Character Name"`, `/tpahere "Character Name"`, `/tpaccept`, `/tpdeny`, `/tpacancel`
- `/heal`, `/feed`, `/fly`, `/god`
- `/broadcast`
- `/vessentials` and `/essentials` status/help

Not included yet:

- EssentialsChat behavior; Vantablack chat is owned by `mod-roleplay`.
- Economy/pay/sell/worth; Vantablack should use platform APIs for durable
  economy contracts.
- Bans/mutes/sanctions; durable enforcement should live in backend/gateway
  flows before adding command wrappers.
- Bukkit/Paper compatibility, `plugin.yml`, Vault, or LuckPerms integration.

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

Runtime `/back` and TPA requests are intentionally in-memory only.

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
