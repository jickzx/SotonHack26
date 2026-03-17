/**
 * geminiPromptParser.js
 *
 * Uses Google Gemini to parse a natural-language Minecraft seed request into
 * structured JSON that the seed search engine can consume.
 *
 * Falls back to the local regex parser (promptPreferences.js) when the API
 * key is missing or the request fails.
 */

const { BIOMES } = require("./biomeList");

const GEMINI_MODEL = process.env.GEMINI_MODEL || "gemini-2.5-flash";

const VALID_GENRES = [
  "ambient",
  "country",
  "electronic",
  "indipop",
  "jazz",
  "metal",
  "default"
];

function getApiKey() {
  return (process.env.GEMINI_API_KEY || "").trim();
}

function buildBiomeSnakeList() {
  return BIOMES.map(b => `"${b}"`).join(", ");
}

function buildSystemPrompt() {
  return [
    "You are a Minecraft seed request parser.",
    "The user will give you a plain-English request about what kind of Minecraft world they want.",
    "",
    "Your job is to extract structured JSON from their message. Return ONLY valid JSON, no markdown fences, no explanation.",
    "",
    "The JSON schema you must return:",
    "{",
    '  "genre": string | null,           // one of: ' + VALID_GENRES.join(", ") + " or null if not mentioned",
    '  "spawnBiome": string | null,       // the biome the user wants to SPAWN IN (snake_case). Only set this when the user explicitly says they want to spawn in / start in a specific biome. null otherwise.',
    '  "requiredNearby": string[],        // biomes or structures that should be NEARBY (snake_case for biomes, camelCase for structures)',
    '  "excludedNearby": string[],        // biomes or structures that should NOT be nearby',
    "}",
    "",
    "IMPORTANT RULES:",
    '- "spawn in taiga" or "start in a taiga biome" means spawnBiome = "taiga", NOT requiredNearby.',
    '- "taiga nearby" or "with a taiga" means requiredNearby = ["taiga"], NOT spawnBiome.',
    '- "ruined portal nearby" means requiredNearby = ["ruined_portal"].',
    '- "mansion nearby" means requiredNearby = ["mansion"].',
    '- "village nearby" means requiredNearby = ["village"].',
    '- "pillager outpost" means requiredNearby = ["pillager_outpost"].',
    '- "jungle temple" means requiredNearby = ["jungle_temple"].',
    '- "stronghold" means requiredNearby = ["stronghold"].',
    '- "igloo" means requiredNearby = ["igloo"].',
    '- "no ocean" or "without ocean" means excludedNearby = ["ocean"].',
    "",
    "Valid biome values (snake_case): " + buildBiomeSnakeList(),
    "",
    'Valid structure values: "mansion", "village", "pillager_outpost", "ruined_portal", "jungle_temple", "igloo", "stronghold".',
    "",
    "If the user does not mention a genre, set genre to null.",
    "If the user does not mention spawning, set spawnBiome to null.",
    "Return empty arrays for requiredNearby / excludedNearby when nothing is mentioned.",
    "Always use snake_case for biome names and structure names in the output."
  ].join("\n");
}

async function callGemini(userPrompt) {
  const apiKey = getApiKey();
  if (!apiKey) {
    return null;
  }

  const url = `https://generativelanguage.googleapis.com/v1beta/models/${GEMINI_MODEL}:generateContent?key=${apiKey}`;

  const body = {
    contents: [
      {
        role: "user",
        parts: [
          { text: buildSystemPrompt() + "\n\nUser request: " + userPrompt }
        ]
      }
    ],
    generationConfig: {
      temperature: 0.1,
      maxOutputTokens: 512
    }
  };

  const response = await fetch(url, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const errorText = await response.text();
    console.warn(`Gemini API error (${response.status}):`, errorText);
    return null;
  }

  const data = await response.json();
  const text = (data.candidates?.[0]?.content?.parts?.[0]?.text || "").trim();

  // Strip markdown code fences if Gemini wraps the JSON
  const cleaned = text.replace(/^```(?:json)?\s*/i, "").replace(/\s*```$/i, "").trim();

  try {
    return JSON.parse(cleaned);
  } catch (err) {
    console.warn("Gemini returned unparseable JSON:", cleaned);
    return null;
  }
}

// Map structure snake_case names to the CloseBy field names used in seeds.json
const STRUCTURE_FIELD_MAP = {
  mansion: "mansionCloseBy",
  woodland_mansion: "mansionCloseBy",
  village: "villageCloseBy",
  pillager_outpost: "pillagerOutpostCloseBy",
  ruined_portal: "ruinedPortalCloseBy",
  jungle_temple: "jungleTempleCloseBy",
  igloo: "iglooCloseBy",
  stronghold: "strongholdCloseBy"
};

// Map structure field to human label
const STRUCTURE_LABEL_MAP = {
  mansionCloseBy: "Woodland Mansion",
  villageCloseBy: "Village",
  pillagerOutpostCloseBy: "Pillager Outpost",
  ruinedPortalCloseBy: "Ruined Portal",
  jungleTempleCloseBy: "Jungle Temple",
  iglooCloseBy: "Igloo",
  strongholdCloseBy: "Stronghold"
};

function snakeToCamel(value) {
  return String(value || "").replace(/_([a-z])/g, (_, c) => c.toUpperCase());
}

function humanizeSnakeCase(value) {
  return String(value || "")
    .split("_")
    .filter(Boolean)
    .map(w => w.charAt(0).toUpperCase() + w.slice(1))
    .join(" ");
}

function resolveFeatureField(name) {
  const normalized = String(name || "").toLowerCase().trim();

  // Check if it's a known structure
  if (STRUCTURE_FIELD_MAP[normalized]) {
    const field = STRUCTURE_FIELD_MAP[normalized];
    return { field, label: STRUCTURE_LABEL_MAP[field] || humanizeSnakeCase(normalized) };
  }

  // It's a biome — convert to camelCase + CloseBy
  const camel = snakeToCamel(normalized);
  const field = `${camel}CloseBy`;
  return { field, label: humanizeSnakeCase(normalized) };
}

/**
 * Parse a user prompt using Gemini and return structured preferences
 * compatible with the server's seed search logic.
 *
 * Returns null if Gemini is unavailable or fails.
 */
async function parsePromptWithGemini(prompt) {
  const text = String(prompt || "").trim();
  if (!text) {
    return null;
  }

  const parsed = await callGemini(text);
  if (!parsed) {
    return null;
  }

  const required = [];
  const excluded = [];

  for (const name of parsed.requiredNearby || []) {
    const feature = resolveFeatureField(name);
    if (feature.field) {
      required.push(feature);
    }
  }

  for (const name of parsed.excludedNearby || []) {
    const feature = resolveFeatureField(name);
    if (feature.field) {
      excluded.push(feature);
    }
  }

  // Resolve spawnBiome — normalize to the snake_case values used in seeds.json
  let spawnBiome = null;
  if (parsed.spawnBiome) {
    spawnBiome = String(parsed.spawnBiome).toLowerCase().replace(/\s+/g, "_").trim();
  }

  // Resolve genre
  let genreKey = null;
  if (parsed.genre && VALID_GENRES.includes(parsed.genre)) {
    genreKey = parsed.genre;
  }

  return {
    text,
    genreKey,
    spawnBiome,
    required,
    excluded,
    hasPreferences: required.length > 0 || excluded.length > 0 || Boolean(spawnBiome)
  };
}

module.exports = {
  parsePromptWithGemini
};
