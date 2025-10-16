# DisenchantLite

A lightweight server-side Fabric mod for Minecraft that adds quality-of-life disenchanting features using the vanilla anvil.

## Features

**Disenchant Tools & Armor**
- Place an enchanted tool/armor in the left slot
- Place a regular book in the right slot
- Get an enchanted book with all enchantments + clean tool back

**Split Enchanted Books**
- Place a multi-enchantment book in the left slot
- Place a regular book in the right slot
- Extract one enchantment at a time from the book

Both operations consume books and XP levels based on enchantment rarity.

## Installation

1. Download and install [Fabric Loader](https://fabricmc.net/use/)
2. Download [Fabric API](https://modrinth.com/mod/fabric-api)
3. Download DisenchantLite
4. Place both mods in your `mods` folder

## Compatibility

- Minecraft: 1.21.8+
- Fabric Loader: 0.17.2+
- Server-side only (will work client-side in singleplayer too)

## License

MIT License - See [LICENSE](LICENSE) for details

## Development

```bash
./gradlew build
```

Built JAR will be in `build/libs/`