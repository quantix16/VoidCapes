# VoidCapes

**VoidCapes** is a modified version of the [Cape Provider mod by litetex](https://modrinth.com/mod/cape-provider), specifically customized to work exclusively with VoidCube's cape service.

You need a cat picture on your computer to use this mod!

## ✨ Features

- **🎯 VoidCube Integration**: Seamlessly connects to VoidCube's cape service for custom cape display
- **👀 Live Preview**: Real-time cape preview with 3D player model in the mod menu
- **🔄 Auto-Refresh**: Automatic cape updates every 3 minutes to show the latest changes
- **⏱️ Countdown Timer**: Live countdown showing time until next automatic refresh
- **🎬 Animated Capes**: Support for high-quality animated capes up to Full HD resolution
- **🖱️ Manual Refresh**: Click the refresh button to instantly update your cape
- **📊 Clean Logging**: All refresh activities logged with `[VoidCapes]` prefix for easy debugging

## 🎨 Getting a Free Cape

Want a custom cape? It's completely free! Simply message **"Xandarian"** on Discord to request your personalized cape.

## �️ Development

### Build the Mod

To compile the mod, run the following Gradle command:

```bash
./gradlew build
```

Or on Windows:

```cmd
.\gradlew.bat build
```

The compiled mod file will be available in `build/libs/` directory.

## �🔧 Technical Details

- **Fabric Mod Loader** compatible
- **Client-side only** - no server installation required
- **Synchronized timers** between auto-refresh and GUI countdown
- **Full HD support** for animated cape textures

## 📝 Credits

This mod is based on the excellent [Cape Provider mod](https://modrinth.com/mod/cape-provider) by **litetex**. VoidCapes is a specialized fork focused on VoidCube's cape service with enhanced features and improved user experience.