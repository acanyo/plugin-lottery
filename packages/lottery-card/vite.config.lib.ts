import { svelte } from "@sveltejs/vite-plugin-svelte";
import { minify } from "terser";
import { fileURLToPath } from "url";
import { defineConfig, type Plugin } from "vite";
import { copyFileSync, mkdirSync, existsSync } from "fs";

// See https://github.com/vitejs/vite/issues/6555
const minifyBundle = (): Plugin => ({
  name: "minify-bundle",
  async generateBundle(_, bundle) {
    for (const asset of Object.values(bundle)) {
      if (asset.type === "chunk") {
        const code = (await minify(asset.code, { sourceMap: false })).code;
        if (code) {
          asset.code = code;
        }
      }
    }
  },
});

const copyToStatic = (): Plugin => ({
  name: "copy-to-static",
  closeBundle() {
    const staticDir = fileURLToPath(new URL("../../src/main/resources/static", import.meta.url));
    if (!existsSync(staticDir)) {
      mkdirSync(staticDir, { recursive: true });
    }
    const distDir = fileURLToPath(new URL("./dist", import.meta.url));
    try {
      copyFileSync(`${distDir}/lottery-card.iife.js`, `${staticDir}/lottery-card.js`);
      copyFileSync(`${distDir}/lottery-card.css`, `${staticDir}/lottery-card.css`);
      console.log("✓ Copied to src/main/resources/static/");
    } catch (e) {
      console.error("Failed to copy files:", e);
    }
  },
});

export default defineConfig({
  plugins: [
    svelte(),
    minifyBundle(),
    copyToStatic(),
  ],
  build: {
    lib: {
      entry: "src/index.ts",
      name: "LotteryCard",
      fileName: "lottery-card",
      formats: ["es", "iife"],
    },
    rollupOptions: {
      output: {
        extend: true,
        assetFileNames: "lottery-card.[ext]",
      },
    },
  },
});
