// Builds the download site in site/public from the app:
//   - download/silentfeed.apk   the signed release build (run `gradlew assembleRelease` first)
//   - version.json               what the app checks for updates
//   - silentfeed.user.js        the same filter as a Safari userscript, for iPhone
//   - index.html                 the page, with the version, size and checksums filled in
// Usage: node site/build.mjs [release notes]
import { createHash } from 'node:crypto';
import { copyFileSync, mkdirSync, readFileSync, writeFileSync, statSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const here = dirname(fileURLToPath(import.meta.url));
const root = join(here, '..');
const out = join(here, 'public');
const gradle = readFileSync(join(root, 'app', 'build.gradle.kts'), 'utf8');
const versionCode = Number(/versionCode = (\d+)/.exec(gradle)[1]);
const versionName = /versionName = "([^"]+)"/.exec(gradle)[1];
const siteUrl = (/"https:\/\/[^"]+\.vercel\.app"/.exec(gradle) || ['"https://silentfeed.vercel.app"'])[0].slice(1, -1);
const notes = process.argv.slice(2).join(' ');

const apk = join(root, 'app', 'build', 'outputs', 'apk', 'release', 'app-release.apk');
mkdirSync(join(out, 'download'), { recursive: true });
copyFileSync(apk, join(out, 'download', 'silentfeed.apk'));
const apkBytes = readFileSync(apk);
const apkSha = createHash('sha256').update(apkBytes).digest('hex');
const apkMb = (statSync(apk).size / 1048576).toFixed(1);

writeFileSync(join(out, 'version.json'), JSON.stringify({ versionCode, versionName, apk: '/download/silentfeed.apk', sha256: apkSha, notes }, null, 2) + '\n');

// The userscript: the app's filter with the iPhone settings (the app's own features off).
const filter = readFileSync(join(root, 'app', 'src', 'main', 'assets', 'web_filter.js'), 'utf8');
const cfg = { hideAds: true, hideSuggested: true, followingFeed: true, blockReels: true, allowDmReels: true, hideExplore: true, skipStoryAds: true, debug: false, app: false };
const userscript = `// ==UserScript==
// @name         SilentFeed
// @description  Instagram with only the people you follow: no ads, no suggested posts, no Reels.
// @version      ${versionName}
// @match        https://www.instagram.com/*
// @run-at       document-start
// @inject-into  page
// @updateURL    ${siteUrl}/silentfeed.user.js
// @downloadURL  ${siteUrl}/silentfeed.user.js
// ==/UserScript==
(function () {
var __rbCfg = ${JSON.stringify(cfg)};
${filter}
})();
`;
writeFileSync(join(out, 'silentfeed.user.js'), userscript);

const page = readFileSync(join(here, 'index.template.html'), 'utf8')
  .replaceAll('{{VERSION}}', versionName)
  .replaceAll('{{APK_MB}}', apkMb)
  .replaceAll('{{APK_SHA256}}', apkSha)
  .replaceAll('{{SITE_URL}}', siteUrl);
writeFileSync(join(out, 'index.html'), page);
console.log(`site built: v${versionName} (${versionCode}), apk ${apkMb} MB, sha256 ${apkSha.slice(0, 16)}…`);
