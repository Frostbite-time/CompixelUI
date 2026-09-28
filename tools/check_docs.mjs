// Check repository documentation without network access or extra dependencies.
// 无需网络或额外依赖，检查仓库文档。
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { execFileSync } from 'node:child_process';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const listed = execFileSync('git', ['ls-files', '--cached', '--others', '--exclude-standard', '-z'], { cwd: root, encoding: 'utf8' });
const files = [...new Set(listed.split('\0').filter(Boolean))]
    .filter(file => /\.md$/i.test(file) && fs.existsSync(path.join(root, file)));
const errors = [];
const usedImages = new Set();
const content = new Map(files.map(file => [file, fs.readFileSync(path.join(root, file), 'utf8')]));
const error = (file, message) => errors.push(`${file}: ${message}`);
// The mod page is pasted into download sites such as CurseForge, so it is English-only and every link is absolute.
// 模组页面会粘贴到 CurseForge 等下载站，因此只用英文，所有链接都用绝对地址。
const modPage = 'docs/mod-page.md';
// Absolute links into this repository must still name an existing file.
// 指向本仓库的绝对链接同样必须指向存在的文件。
const repositoryUrl = /^https:\/\/(?:github\.com\/Frostbite-time\/compose-mc\/(?:blob|tree)\/main|raw\.githubusercontent\.com\/Frostbite-time\/compose-mc\/main)\/([^?]+)$/;

function prose(text) {
    let fence = null;
    return text.split(/\r?\n/).filter(line => {
        const marker = /^\s*(`{3,}|~{3,})/.exec(line)?.[1];
        if (marker) {
            if (!fence) fence = marker;
            else if (marker[0] === fence[0] && marker.length >= fence.length) fence = null;
            return false;
        }
        return !fence;
    }).join('\n');
}

function anchors(text) {
    const ids = new Set();
    for (const match of prose(text).matchAll(/^#{1,6}\s+(.+?)\s*#*$/gm)) {
        const base = match[1].toLowerCase().replace(/<[^>]+>/g, '')
            .replace(/[^\p{L}\p{N}\p{M}_\- ]/gu, '').replace(/ /g, '-');
        let id = base;
        for (let suffix = 1; ids.has(id); suffix++) id = `${base}-${suffix}`;
        ids.add(id);
    }
    for (const match of text.matchAll(/<a\s+(?:id|name)=["']([^"']+)["']/g)) ids.add(match[1]);
    return ids;
}

// Check exact spelling too: a path accepted on Windows may fail on Linux.
// 同时检查大小写，避免 Windows 可用的路径在 Linux 失效。
function exactPath(relative) {
    let directory = root;
    for (const segment of relative.split('/').filter(Boolean)) {
        if (!fs.existsSync(directory) || !fs.statSync(directory).isDirectory()) return false;
        if (!fs.readdirSync(directory).includes(segment)) return false;
        directory = path.join(directory, segment);
    }
    return true;
}

let links = 0;
for (const [file, text] of content) {
    const body = prose(text);
    for (const match of body.matchAll(/(!?)\[([^\]\n]*)\]\((<[^>]+>|[^\s)]+)(?:\s+"[^"]*")?\)/g)) {
        const [, image, label, raw] = match;
        const href = raw.replace(/^<|>$/g, '');
        if (image && !label.trim()) error(file, 'Image needs alternative text / 图片缺少替代文字');
        const inRepository = repositoryUrl.exec(href)?.[1];
        if (!inRepository) {
            if (/^(https?:|mailto:)/i.test(href)) continue;
            if (file === modPage) {
                error(file, `Use an absolute URL / 请使用绝对链接: ${href}`);
                continue;
            }
            if (/^[a-z][a-z\d+.-]*:/i.test(href) || href.startsWith('/') || href.includes('\\')) {
                error(file, `Use a repository-relative URL / 请使用仓库相对链接: ${href}`);
                continue;
            }
        }
        links++;
        let destination, fragment;
        try { [destination, fragment] = (inRepository ?? href).split('#').map(decodeURIComponent); }
        catch { error(file, `Malformed URL / 链接编码错误: ${href}`); continue; }
        const base = inRepository ? '' : path.posix.dirname(file);
        const target = destination ? path.posix.normalize(path.posix.join(base, destination)) : file;
        if (target === '..' || target.startsWith('../') || !exactPath(target)) {
            error(file, `Missing target or case mismatch / 目标缺失或大小写不符: ${href}`);
            continue;
        }
        if (fragment && target.endsWith('.md') && !anchors(fs.readFileSync(path.join(root, target), 'utf8')).has(fragment)) {
            error(file, `Missing heading / 标题锚点缺失: ${href}`);
        }
        if (image) usedImages.add(target);
    }
}

for (const file of files) {
    let counterpart;
    if (file.startsWith('docs/en/')) counterpart = file.replace('docs/en/', 'docs/zh-CN/');
    else if (file.startsWith('docs/zh-CN/')) counterpart = file.replace('docs/zh-CN/', 'docs/en/');
    else if (file === 'README.md') counterpart = 'README.zh-CN.md';
    else if (file === 'README.zh-CN.md') counterpart = 'README.md';
    if (counterpart) {
        if (!content.has(counterpart)) error(file, `Missing translation / 缺少翻译: ${counterpart}`);
        const expected = path.posix.relative(path.posix.dirname(file), counterpart);
        if (!content.get(file).includes(`](${expected})`)) error(file, 'Missing language switch / 缺少语言切换链接');
    } else if (file !== modPage && (!/[\u3400-\u9fff]/.test(prose(content.get(file))) || !/[A-Za-z]{3}/.test(prose(content.get(file))))) {
        error(file, 'Standalone page must include English and Chinese / 单页文档需要中英文内容');
    }
}

for (const entry of fs.readdirSync(path.join(root, 'docs/assets'))) {
    if (/\.(png|jpe?g|gif|webp|svg)$/i.test(entry) && !usedImages.has(`docs/assets/${entry}`)) {
        error(`docs/assets/${entry}`, 'Unreferenced image / 未引用的图片');
    }
}
if (errors.length) {
    console.error(errors.join('\n'));
    process.exitCode = 1;
} else {
    console.log(`PASS: ${files.length} Markdown files, ${links} local links, ${usedImages.size} images; bilingual pairs complete.`);
    console.log('通过：本地链接、图片引用和中英文页面配对检查。');
}
