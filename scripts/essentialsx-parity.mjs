#!/usr/bin/env node

import fs from "node:fs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = path.dirname(fileURLToPath(import.meta.url));
const repoRoot = path.resolve(scriptDir, "..");
const defaultManifest = "docs/fabric-command-manifest.json";
const defaultOutput = "docs/essentialsx-parity.md";

function main() {
  const options = parseArgs(process.argv.slice(2));
  if (options.help) {
    printHelp();
    return;
  }

  const manifestPath = resolveRepoPath(options.manifest);
  const outputPath = resolveRepoPath(options.output);
  const manifest = readManifest(manifestPath);
  const parsedPlugins = findPluginYmls(repoRoot).map(parsePluginYml);
  const analysis = analyze(parsedPlugins, manifest);
  const markdown = renderMarkdown(analysis, manifest, manifestPath);

  if (options.json) {
    process.stdout.write(`${JSON.stringify(summarizeForJson(analysis), null, 2)}\n`);
  }

  if (options.stdout) {
    process.stdout.write(markdown);
  } else if (options.check) {
    const existing = fs.existsSync(outputPath) ? fs.readFileSync(outputPath, "utf8") : null;
    if (existing !== markdown) {
      console.error(`Parity report is stale: ${relative(outputPath)}`);
      console.error(`Run: node scripts/essentialsx-parity.mjs`);
      process.exitCode = 1;
    } else {
      printSummary(analysis);
      console.log(`Parity report is up to date: ${relative(outputPath)}`);
    }
  } else {
    fs.mkdirSync(path.dirname(outputPath), { recursive: true });
    fs.writeFileSync(outputPath, markdown, "utf8");
    printSummary(analysis);
    console.log(`Wrote ${relative(outputPath)}`);
  }

  if (options.failOnGaps && hasParityGaps(analysis)) {
    console.error("Parity gaps remain; refusing success because --fail-on-gaps was supplied.");
    process.exitCode = 1;
  }
}

function parseArgs(argv) {
  const options = {
    manifest: defaultManifest,
    output: defaultOutput,
    stdout: false,
    check: false,
    failOnGaps: false,
    json: false,
    help: false
  };

  for (let index = 0; index < argv.length; index += 1) {
    const arg = argv[index];
    if (arg === "--manifest") {
      options.manifest = requireValue(argv, index, arg);
      index += 1;
    } else if (arg === "--output") {
      options.output = requireValue(argv, index, arg);
      index += 1;
    } else if (arg === "--stdout" || arg === "--no-write") {
      options.stdout = true;
    } else if (arg === "--check") {
      options.check = true;
    } else if (arg === "--fail-on-gaps") {
      options.failOnGaps = true;
    } else if (arg === "--json") {
      options.json = true;
    } else if (arg === "--help" || arg === "-h") {
      options.help = true;
    } else {
      throw new Error(`Unknown argument: ${arg}`);
    }
  }

  return options;
}

function requireValue(argv, index, arg) {
  const value = argv[index + 1];
  if (!value || value.startsWith("--")) {
    throw new Error(`${arg} requires a value`);
  }
  return value;
}

function printHelp() {
  console.log(`Usage: node scripts/essentialsx-parity.mjs [options]

Options:
  --manifest <path>   Fabric command manifest (default: ${defaultManifest})
  --output <path>     Markdown report output (default: ${defaultOutput})
  --check             Fail if the report file is stale
  --fail-on-gaps      Fail when command, alias, or usage parity gaps remain
  --stdout            Print Markdown instead of writing the report
  --json              Print a machine-readable summary
  --help              Show this help
`);
}

function resolveRepoPath(inputPath) {
  return path.isAbsolute(inputPath) ? inputPath : path.resolve(repoRoot, inputPath);
}

function relative(inputPath) {
  return path.relative(repoRoot, inputPath) || ".";
}

function readManifest(manifestPath) {
  const manifest = JSON.parse(fs.readFileSync(manifestPath, "utf8"));
  if (manifest.schemaVersion !== 1) {
    throw new Error(`Unsupported manifest schemaVersion in ${relative(manifestPath)}`);
  }
  if (!Array.isArray(manifest.commands)) {
    throw new Error(`Manifest must contain a commands array: ${relative(manifestPath)}`);
  }
  return manifest;
}

function findPluginYmls(root) {
  const results = [];
  const skipDirectories = new Set([".git", ".gradle", ".idea", "build", "out"]);

  function walk(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const fullPath = path.join(directory, entry.name);
      if (entry.isDirectory()) {
        if (!skipDirectories.has(entry.name)) {
          walk(fullPath);
        }
        continue;
      }

      if (entry.isFile() && entry.name === "plugin.yml") {
        const normalized = relative(fullPath).split(path.sep).join("/");
        if (normalized.endsWith("/src/main/resources/plugin.yml")) {
          results.push(fullPath);
        }
      }
    }
  }

  walk(root);
  return results.sort((left, right) => relative(left).localeCompare(relative(right)));
}

function parsePluginYml(filePath) {
  const lines = fs.readFileSync(filePath, "utf8").split(/\r?\n/);
  const fallbackName = relative(filePath).split(path.sep)[0];
  const nameLine = lines.find(line => line.match(/^name:\s*/));
  const pluginName = nameLine ? parseScalar(nameLine.replace(/^name:\s*/, "")) : fallbackName;
  const commands = [];
  let inCommands = false;
  let currentCommand = null;

  for (const line of lines) {
    if (!inCommands) {
      if (line.match(/^commands:\s*$/)) {
        inCommands = true;
      }
      continue;
    }

    if (line.match(/^[^\s#][^:]*:\s*$/) && !line.match(/^commands:\s*$/)) {
      break;
    }

    const commandMatch = line.match(/^  ([A-Za-z0-9_-]+):\s*(?:#.*)?$/);
    if (commandMatch) {
      currentCommand = {
        name: normalizeName(commandMatch[1]),
        source: pluginName,
        sourcePath: relative(filePath),
        description: "",
        usage: "",
        aliases: []
      };
      commands.push(currentCommand);
      continue;
    }

    if (!currentCommand) {
      continue;
    }

    const propertyMatch = line.match(/^    ([A-Za-z0-9_-]+):\s*(.*)$/);
    if (!propertyMatch) {
      continue;
    }

    const key = propertyMatch[1];
    const value = propertyMatch[2];
    if (key === "description") {
      currentCommand.description = parseScalar(value);
    } else if (key === "usage") {
      currentCommand.usage = parseScalar(value);
    } else if (key === "aliases") {
      currentCommand.aliases = parseAliases(value);
    }
  }

  return {
    name: pluginName,
    path: relative(filePath),
    commands
  };
}

function parseAliases(rawValue) {
  const value = rawValue.trim();
  if (!value) {
    return [];
  }
  if (value.startsWith("[") && value.endsWith("]")) {
    return value
      .slice(1, -1)
      .split(",")
      .map(parseScalar)
      .map(normalizeName)
      .filter(Boolean);
  }
  return [normalizeName(parseScalar(value))].filter(Boolean);
}

function parseScalar(rawValue) {
  let value = rawValue.trim();
  if (
    (value.startsWith("'") && value.endsWith("'")) ||
    (value.startsWith("\"") && value.endsWith("\""))
  ) {
    value = value.slice(1, -1);
  }
  return value.trim();
}

function normalizeName(name) {
  return String(name).trim().toLowerCase();
}

function normalizeUsage(usage) {
  return String(usage).replace(/\s+/g, " ").trim().toLowerCase();
}

function analyze(parsedPlugins, manifest) {
  const upstreamCommands = parsedPlugins.flatMap(plugin => plugin.commands);
  const upstreamByName = new Map();
  const duplicateUpstreamCommands = [];

  for (const command of upstreamCommands) {
    if (upstreamByName.has(command.name)) {
      duplicateUpstreamCommands.push([upstreamByName.get(command.name), command]);
    } else {
      upstreamByName.set(command.name, command);
    }
  }

  const upstreamAliasToCommand = new Map();
  for (const command of upstreamByName.values()) {
    for (const alias of command.aliases) {
      if (!upstreamAliasToCommand.has(alias)) {
        upstreamAliasToCommand.set(alias, []);
      }
      upstreamAliasToCommand.get(alias).push(command.name);
    }
  }

  const manifestCommands = manifest.commands.map(normalizeManifestCommand);
  const manifestCommandNames = new Map();
  const duplicateManifestNames = [];
  for (const command of manifestCommands) {
    if (manifestCommandNames.has(command.name)) {
      duplicateManifestNames.push(command.name);
    }
    manifestCommandNames.set(command.name, command);
  }

  const fabricExposedNames = new Set();
  for (const command of manifestCommands) {
    fabricExposedNames.add(command.name);
    for (const alias of command.aliases) {
      fabricExposedNames.add(alias);
    }
  }

  const fabricByUpstream = new Map();
  const invalidUpstreamMappings = [];
  for (const command of manifestCommands) {
    if (!command.upstream) {
      continue;
    }
    if (!upstreamByName.has(command.upstream)) {
      invalidUpstreamMappings.push(command);
      continue;
    }
    if (!fabricByUpstream.has(command.upstream)) {
      fabricByUpstream.set(command.upstream, []);
    }
    fabricByUpstream.get(command.upstream).push(command);
  }

  const upstreamRows = [...upstreamByName.values()].map(command => {
    const matchedFabric = uniqueCommands([
      ...(fabricByUpstream.get(command.name) ?? []),
      ...manifestCommands.filter(fabric => fabric.name === command.name || fabric.aliases.includes(command.name))
    ]);
    const canonicalUsage = canonicalizeUpstreamUsage(command);
    const fabricUsages = matchedFabric.flatMap(fabric => fabric.usages);
    const usageMatches = Boolean(canonicalUsage) && fabricUsages.some(usage => normalizeUsage(usage) === normalizeUsage(canonicalUsage));
    const coveredAliases = command.aliases.filter(alias => fabricExposedNames.has(alias));
    const missingAliases = command.aliases.filter(alias => !fabricExposedNames.has(alias));

    return {
      ...command,
      matched: matchedFabric.length > 0,
      matchedFabric,
      canonicalUsage,
      fabricUsages,
      usageMatches,
      coveredAliases,
      missingAliases
    };
  });

  const extraFabricCommands = manifestCommands.filter(command => {
    if (command.upstream) {
      return false;
    }
    return !upstreamByName.has(command.name) && !upstreamAliasToCommand.has(command.name);
  });

  const implementedRows = upstreamRows.filter(row => row.matched);
  const missingRows = upstreamRows.filter(row => !row.matched);
  const aliasTotal = upstreamRows.reduce((sum, row) => sum + row.aliases.length, 0);
  const coveredAliasTotal = upstreamRows.reduce((sum, row) => sum + row.coveredAliases.length, 0);
  const usageDiffRows = implementedRows.filter(row => row.canonicalUsage && !row.usageMatches);
  const usageGapRows = usageDiffRows.filter(row => hasUsageBehaviorGap(row));
  const syntaxOnlyUsageDiffRows = usageDiffRows.filter(row => !hasUsageBehaviorGap(row));
  const exactParityRows = implementedRows.filter(row => row.usageMatches && row.missingAliases.length === 0);

  return {
    parsedPlugins,
    manifestCommands,
    upstreamRows,
    implementedRows,
    missingRows,
    usageDiffRows,
    usageGapRows,
    syntaxOnlyUsageDiffRows,
    exactParityRows,
    extraFabricCommands,
    invalidUpstreamMappings,
    duplicateUpstreamCommands,
    duplicateManifestNames,
    aliasTotal,
    coveredAliasTotal
  };
}

function normalizeManifestCommand(command) {
  const name = normalizeName(command.name);
  if (!name) {
    throw new Error("Manifest command is missing name");
  }
  return {
    name,
    usages: ensureStringArray(command.usages, `usages for ${name}`),
    aliases: ensureStringArray(command.aliases ?? [], `aliases for ${name}`).map(normalizeName),
    upstream: command.upstream === null || command.upstream === undefined ? null : normalizeName(command.upstream),
    notes: command.notes ?? ""
  };
}

function ensureStringArray(value, label) {
  if (!Array.isArray(value) || value.some(item => typeof item !== "string")) {
    throw new Error(`Manifest ${label} must be an array of strings`);
  }
  return value;
}

function uniqueCommands(commands) {
  const seen = new Set();
  const unique = [];
  for (const command of commands) {
    if (!seen.has(command.name)) {
      seen.add(command.name);
      unique.push(command);
    }
  }
  return unique;
}

function canonicalizeUpstreamUsage(command) {
  if (!command.usage) {
    return "";
  }
  return command.usage
    .replaceAll("/<command>", `/${command.name}`)
    .replaceAll("/<alias>", `/${command.name}`);
}

function renderMarkdown(analysis, manifest, manifestPath) {
  const lines = [];
  lines.push("# EssentialsX Parity Report");
  lines.push("");
  lines.push(`Generated by ${code("node scripts/essentialsx-parity.mjs")} from retained upstream ${code("plugin.yml")} files and ${code(relative(manifestPath))}.`);
  lines.push("");

  if (Array.isArray(manifest.reportNotes) && manifest.reportNotes.length > 0) {
    lines.push("## Notes");
    lines.push("");
    for (const note of manifest.reportNotes) {
      lines.push(`- ${note}`);
    }
    lines.push("");
  }

  lines.push("## Summary");
  lines.push("");
  lines.push("| Metric | Value |");
  lines.push("| --- | ---: |");
  lines.push(row("Upstream plugin.yml files scanned", analysis.parsedPlugins.length));
  lines.push(row("Upstream command roots", analysis.upstreamRows.length));
  lines.push(row("Fabric manifest roots", analysis.manifestCommands.length));
  lines.push(row("Upstream roots with Fabric coverage", `${analysis.implementedRows.length} / ${analysis.upstreamRows.length} (${percent(analysis.implementedRows.length, analysis.upstreamRows.length)})`));
  lines.push(row("Exact usage and alias parity roots", `${analysis.exactParityRows.length} / ${analysis.upstreamRows.length} (${percent(analysis.exactParityRows.length, analysis.upstreamRows.length)})`));
  lines.push(row("Upstream aliases exposed by Fabric", `${analysis.coveredAliasTotal} / ${analysis.aliasTotal} (${percent(analysis.coveredAliasTotal, analysis.aliasTotal)})`));
  lines.push(row("Covered roots with usage differences", analysis.usageDiffRows.length));
  lines.push(row("Usage differences marked as behavior gaps", analysis.usageGapRows.length));
  lines.push(row("Usage differences classified as syntax-only", analysis.syntaxOnlyUsageDiffRows.length));
  lines.push(row("Missing upstream roots", analysis.missingRows.length));
  lines.push("");

  lines.push("## Upstream Sources");
  lines.push("");
  lines.push("| Plugin | Source | Commands |");
  lines.push("| --- | --- | ---: |");
  for (const plugin of analysis.parsedPlugins) {
    lines.push(row(plugin.name, code(plugin.path), plugin.commands.length));
  }
  lines.push("");

  lines.push("## Fabric Manifest");
  lines.push("");
  lines.push("| Root | Upstream root | Usages | Notes |");
  lines.push("| --- | --- | --- | --- |");
  for (const command of analysis.manifestCommands) {
    lines.push(row(code(command.name), command.upstream ? code(command.upstream) : "none", codeList(command.usages), command.notes || ""));
  }
  lines.push("");

  lines.push("## Covered Upstream Roots");
  lines.push("");
  lines.push("| Upstream root | Plugin | Fabric roots | Upstream usage | Fabric usages | Alias coverage | Notes |");
  lines.push("| --- | --- | --- | --- | --- | ---: | --- |");
  for (const rowData of analysis.implementedRows) {
    const fabricRoots = rowData.matchedFabric.map(command => command.name);
    const notes = rowData.matchedFabric.map(command => command.notes).filter(Boolean).join("; ");
    lines.push(row(
      code(rowData.name),
      rowData.source,
      codeList(fabricRoots),
      rowData.canonicalUsage ? code(rowData.canonicalUsage) : "none",
      codeList(rowData.fabricUsages),
      `${rowData.coveredAliases.length} / ${rowData.aliases.length}`,
      notes
    ));
  }
  lines.push("");

  lines.push("## Alias Gaps For Covered Roots");
  lines.push("");
  lines.push("| Upstream root | Covered aliases | Missing aliases |");
  lines.push("| --- | --- | --- |");
  for (const rowData of analysis.implementedRows.filter(row => row.aliases.length > 0)) {
    lines.push(row(code(rowData.name), codeList(rowData.coveredAliases), codeList(rowData.missingAliases)));
  }
  lines.push("");

  lines.push("## Usage Differences For Covered Roots");
  lines.push("");
  if (analysis.usageDiffRows.length === 0) {
    lines.push("No covered upstream roots have usage differences.");
  } else {
    lines.push("| Upstream root | Classification | Upstream usage | Fabric usages | Notes |");
    lines.push("| --- | --- | --- | --- | --- |");
    for (const rowData of analysis.usageDiffRows) {
      const notes = rowData.matchedFabric.map(command => command.notes).filter(Boolean).join("; ");
      lines.push(row(
        code(rowData.name),
        hasUsageBehaviorGap(rowData) ? "behavior gap" : "syntax-only",
        code(rowData.canonicalUsage),
        codeList(rowData.fabricUsages),
        notes
      ));
    }
  }
  lines.push("");

  lines.push("## Missing Upstream Roots");
  lines.push("");
  if (analysis.missingRows.length === 0) {
    lines.push("No missing upstream roots.");
  } else {
    lines.push("| Plugin | Missing roots |");
    lines.push("| --- | --- |");
    for (const [source, rows] of groupBy(analysis.missingRows, rowData => rowData.source)) {
      lines.push(row(source, codeList(rows.map(rowData => rowData.name))));
    }
  }
  lines.push("");

  lines.push("## Manifest Diagnostics");
  lines.push("");
  lines.push("| Diagnostic | Result |");
  lines.push("| --- | --- |");
  lines.push(row("Extra Fabric-only roots", codeList(analysis.extraFabricCommands.map(command => command.name))));
  lines.push(row("Invalid upstream mappings", codeList(analysis.invalidUpstreamMappings.map(command => `${command.name}->${command.upstream}`))));
  lines.push(row("Duplicate upstream roots", analysis.duplicateUpstreamCommands.length));
  lines.push(row("Duplicate manifest roots", codeList(analysis.duplicateManifestNames)));
  lines.push("");
  lines.push("## Running The Report");
  lines.push("");
  lines.push("```bash");
  lines.push("node scripts/essentialsx-parity.mjs");
  lines.push("node scripts/essentialsx-parity.mjs --check");
  lines.push("node scripts/essentialsx-parity.mjs --fail-on-gaps");
  lines.push("```");
  lines.push("");

  return `${lines.join("\n")}\n`;
}

function row(...cells) {
  return `| ${cells.map(tableCell).join(" | ")} |`;
}

function tableCell(value) {
  return String(value).replace(/\|/g, "\\|").replace(/\n/g, "<br>");
}

function code(value) {
  return `\`${String(value).replace(/`/g, "\\`")}\``;
}

function codeList(values) {
  const list = [...new Set(values.filter(Boolean))];
  return list.length > 0 ? list.map(code).join(", ") : "none";
}

function percent(numerator, denominator) {
  if (denominator === 0) {
    return "100.0%";
  }
  return `${((numerator / denominator) * 100).toFixed(1)}%`;
}

function groupBy(values, keyFunction) {
  const groups = new Map();
  for (const value of values) {
    const key = keyFunction(value);
    if (!groups.has(key)) {
      groups.set(key, []);
    }
    groups.get(key).push(value);
  }
  return groups;
}

function printSummary(analysis) {
  console.log("EssentialsX parity report");
  console.log(`Upstream command roots: ${analysis.upstreamRows.length}`);
  console.log(`Fabric manifest roots: ${analysis.manifestCommands.length}`);
  console.log(`Covered upstream roots: ${analysis.implementedRows.length}/${analysis.upstreamRows.length} (${percent(analysis.implementedRows.length, analysis.upstreamRows.length)})`);
  console.log(`Aliases exposed: ${analysis.coveredAliasTotal}/${analysis.aliasTotal} (${percent(analysis.coveredAliasTotal, analysis.aliasTotal)})`);
  console.log(`Usage differences on covered roots: ${analysis.usageDiffRows.length}`);
  console.log(`Usage behavior gaps: ${analysis.usageGapRows.length}`);
  console.log(`Syntax-only usage differences: ${analysis.syntaxOnlyUsageDiffRows.length}`);
}

function hasParityGaps(analysis) {
  return analysis.missingRows.length > 0 ||
    analysis.coveredAliasTotal < analysis.aliasTotal ||
    analysis.usageGapRows.length > 0 ||
    analysis.invalidUpstreamMappings.length > 0 ||
    analysis.duplicateManifestNames.length > 0 ||
    analysis.duplicateUpstreamCommands.length > 0;
}

function summarizeForJson(analysis) {
  return {
    upstreamCommandRoots: analysis.upstreamRows.length,
    fabricManifestRoots: analysis.manifestCommands.length,
    coveredUpstreamRoots: analysis.implementedRows.length,
    missingUpstreamRoots: analysis.missingRows.length,
    upstreamAliases: analysis.aliasTotal,
    exposedAliases: analysis.coveredAliasTotal,
    usageDifferences: analysis.usageDiffRows.length,
    usageBehaviorGaps: analysis.usageGapRows.length,
    syntaxOnlyUsageDifferences: analysis.syntaxOnlyUsageDiffRows.length,
    exactParityRoots: analysis.exactParityRows.length,
    extraFabricOnlyRoots: analysis.extraFabricCommands.map(command => command.name),
    invalidUpstreamMappings: analysis.invalidUpstreamMappings.map(command => ({
      command: command.name,
      upstream: command.upstream
    }))
  };
}

function hasUsageBehaviorGap(rowData) {
  const notes = rowData.matchedFabric
    .map(command => command.notes)
    .filter(Boolean)
    .join(" ")
    .toLowerCase();
  if (!notes) {
    return false;
  }

  return [
    /\bnot (?:complete|configured|implemented|wired|supported|available|replicated|permission-integrated|safe|fully)\b/,
    /\bno (?:bed|page|per-|permission|offline|yaw|pitch|vault|backend|bukkit|data|delay|range|full|custom|item|player|target|world|selector|filter|metadata|integration|profile|bridge)/,
    /\bonly[.;,]?\s+no\b/,
    /\b(?:requires|needs) [^.]*\bbridge\b/,
    /\bunsupported\b/,
    /\bdisabled\b/,
    /\bcompatibility root\b/,
    /\bplaceholder\b/,
    /\blimitation\b/,
    /\bpartial\b/
  ].some(pattern => pattern.test(notes));
}

main();
