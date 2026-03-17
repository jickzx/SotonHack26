const { BIOMES } = require("./biomeList");
const { findGenreKeyInText } = require("./genreProfiles");

const NEGATION_PATTERN = /(?:^|\b)(?:no|not|without|avoid|excluding?|skip|minus|don't want|dont want|do not want)(?:\s+\w+){0,4}\s*$/i;

// Patterns that indicate the user wants to SPAWN IN a biome, not just have it nearby
const SPAWN_PATTERNS = [
  /(?:spawn|start|begin|land|drop|put)\s+(?:me\s+)?(?:in|at|on)\s+(?:a\s+|an\s+|the\s+)?/gi,
  /(?:spawns?\s+(?:me\s+)?(?:in|at|on)\s+(?:a\s+|an\s+|the\s+)?)/gi
];

const STRUCTURE_DEFINITIONS = [
  {
    field: "mansionCloseBy",
    label: "Woodland Mansion",
    aliases: ["woodland mansion", "mansion", "pillager mansion"]
  },
  {
    field: "villageCloseBy",
    label: "Village",
    aliases: ["village", "villages"]
  },
  {
    field: "pillagerOutpostCloseBy",
    label: "Pillager Outpost",
    aliases: ["pillager outpost", "outpost", "pillager tower"]
  },
  {
    field: "ruinedPortalCloseBy",
    label: "Ruined Portal",
    aliases: ["ruined portal"]
  },
  {
    field: "jungleTempleCloseBy",
    label: "Jungle Temple",
    aliases: ["jungle temple"]
  },
  {
    field: "iglooCloseBy",
    label: "Igloo",
    aliases: ["igloo"]
  },
  {
    field: "strongholdCloseBy",
    label: "Stronghold",
    aliases: ["stronghold"]
  }
];

const BIOME_ALIAS_OVERRIDES = {
  sunflowerPlainsCloseBy: ["sunflower plains"],
  flowerForestCloseBy: ["flower forest"],
  birchForestCloseBy: ["birch forest"],
  oldGrowthBirchForestCloseBy: ["old growth birch forest"],
  darkForestCloseBy: ["dark forest", "roofed forest"],
  sparseJungleCloseBy: ["sparse jungle"],
  bambooJungleCloseBy: ["bamboo jungle"],
  savannaPlateauCloseBy: ["savanna plateau"],
  woodedBadlandsCloseBy: ["wooded badlands"],
  erodedBadlandsCloseBy: ["eroded badlands"],
  snowySlopesCloseBy: ["snowy slopes"],
  jaggedPeaksCloseBy: ["jagged peaks"],
  frozenPeaksCloseBy: ["frozen peaks"],
  stonyPeaksCloseBy: ["stony peaks"],
  cherryGroveCloseBy: ["cherry grove", "cherry blossom biome"],
  oldGrowthPineTaigaCloseBy: ["old growth pine taiga"],
  oldGrowthSpruceTaigaCloseBy: ["old growth spruce taiga"],
  snowyTaigaCloseBy: ["snowy taiga"],
  mangroveSwampCloseBy: ["mangrove swamp", "mangrove"],
  mushroomFieldsCloseBy: ["mushroom fields", "mushroom island"],
  snowyBeachCloseBy: ["snowy beach"],
  stonyShoreCloseBy: ["stony shore", "stone shore"],
  frozenRiverCloseBy: ["frozen river"],
  coldOceanCloseBy: ["cold ocean"],
  deepOceanCloseBy: ["deep ocean"],
  lukewarmOceanCloseBy: ["lukewarm ocean"],
  warmOceanCloseBy: ["warm ocean"],
  frozenOceanCloseBy: ["frozen ocean"]
};

function humanizeSnakeCase(value) {
  return String(value || "")
    .split("_")
    .filter(Boolean)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

function toCamelCase(value) {
  return String(value || "").replace(/_([a-z])/g, (_, letter) => letter.toUpperCase());
}

function unique(values) {
  return [...new Set(values.filter(Boolean))];
}

function escapeRegex(value) {
  return String(value || "").replace(/[.*+?^${}()|[\]\\]/g, "\\$&");
}

function aliasToPattern(alias) {
  return escapeRegex(alias).replace(/\s+/g, "[\\s-]+");
}

function buildBiomeDefinitions() {
  return BIOMES.map(biome => {
    const camelName = toCamelCase(biome);
    const field = `${camelName}CloseBy`;
    const label = humanizeSnakeCase(biome);
    const aliases = unique([
      label.toLowerCase(),
      ...(BIOME_ALIAS_OVERRIDES[field] || [])
    ]);

    return {
      field,
      label,
      snakeName: biome,
      aliases
    };
  });
}

const BIOME_DEFINITIONS = buildBiomeDefinitions();

const FEATURE_DEFINITIONS = [...STRUCTURE_DEFINITIONS, ...BIOME_DEFINITIONS].sort(
  (left, right) => right.label.length - left.label.length
);

const FEATURE_LOOKUP = new Map(FEATURE_DEFINITIONS.map(definition => [definition.field, definition]));

function detectPreference(text, definition) {
  let lastMatch = null;

  for (const alias of definition.aliases) {
    const pattern = new RegExp(`\\b${aliasToPattern(alias)}\\b`, "gi");
    let match = pattern.exec(text);

    while (match) {
      const index = match.index || 0;
      const beforeMatch = text.slice(Math.max(0, index - 48), index).replace(/\s+/g, " ").trim();

      lastMatch = {
        kind: NEGATION_PATTERN.test(beforeMatch) ? "exclude" : "include",
        index
      };

      match = pattern.exec(text);
    }
  }

  return lastMatch ? lastMatch.kind : null;
}

/**
 * Detect if the text contains a "spawn in <biome>" pattern.
 * Returns the snake_case biome name if found, null otherwise.
 */
function detectSpawnBiome(text) {
  // Sort biome definitions by alias length descending so longer names match first
  const sorted = [...BIOME_DEFINITIONS].sort(
    (a, b) => Math.max(...b.aliases.map(x => x.length)) - Math.max(...a.aliases.map(x => x.length))
  );

  for (const pattern of SPAWN_PATTERNS) {
    // Reset lastIndex since we reuse the regex
    pattern.lastIndex = 0;
    let spawnMatch;

    while ((spawnMatch = pattern.exec(text)) !== null) {
      const afterSpawn = text.slice(spawnMatch.index + spawnMatch[0].length);

      for (const biome of sorted) {
        for (const alias of biome.aliases) {
          const biomePattern = new RegExp(`^${aliasToPattern(alias)}\\b`, "i");
          if (biomePattern.test(afterSpawn)) {
            return biome.snakeName;
          }
        }
      }
    }
  }

  return null;
}

/**
 * Detect all alias matches in the text for a given definition,
 * returning an array of { kind, index, length } for each match.
 */
function findAllMatches(text, definition) {
  const matches = [];

  for (const alias of definition.aliases) {
    const pattern = new RegExp(`\\b${aliasToPattern(alias)}\\b`, "gi");
    let match = pattern.exec(text);

    while (match) {
      const index = match.index || 0;
      const beforeMatch = text.slice(Math.max(0, index - 48), index).replace(/\s+/g, " ").trim();

      matches.push({
        kind: NEGATION_PATTERN.test(beforeMatch) ? "exclude" : "include",
        index,
        length: match[0].length
      });

      match = pattern.exec(text);
    }
  }

  return matches;
}

function parsePromptPreferences(prompt = "") {
  const text = String(prompt || "").toLowerCase();
  const required = [];
  const excluded = [];
  const spawnBiome = detectSpawnBiome(text);

  // Track which character ranges have been claimed by a longer match
  // to prevent "dark forest" from also matching "forest" separately.
  // FEATURE_DEFINITIONS is already sorted longest-label-first.
  const claimed = []; // array of { start, end }

  function isOverlapping(index, length) {
    const end = index + length;
    return claimed.some(c => index < c.end && end > c.start);
  }

  for (const definition of FEATURE_DEFINITIONS) {
    const matches = findAllMatches(text, definition);

    // Filter out matches that overlap with already-claimed ranges
    const unclaimed = matches.filter(m => !isOverlapping(m.index, m.length));

    if (unclaimed.length === 0) {
      continue;
    }

    // Use the last unclaimed match to determine include/exclude (consistent with original logic)
    const lastMatch = unclaimed[unclaimed.length - 1];

    if (lastMatch.kind === "include") {
      if (spawnBiome && definition.snakeName === spawnBiome) {
        // Still claim the range so shorter substrings don't match
        for (const m of unclaimed) {
          claimed.push({ start: m.index, end: m.index + m.length });
        }
        continue;
      }
      required.push(definition);
    } else {
      excluded.push(definition);
    }

    // Claim all matched ranges
    for (const m of unclaimed) {
      claimed.push({ start: m.index, end: m.index + m.length });
    }
  }

  return {
    text: String(prompt || "").trim(),
    genreKey: findGenreKeyInText(text),
    spawnBiome,
    required,
    excluded,
    hasPreferences: required.length > 0 || excluded.length > 0 || Boolean(spawnBiome)
  };
}

function getFeatureDefinition(field) {
  return FEATURE_LOOKUP.get(field) || null;
}

module.exports = {
  FEATURE_DEFINITIONS,
  getFeatureDefinition,
  parsePromptPreferences
};
