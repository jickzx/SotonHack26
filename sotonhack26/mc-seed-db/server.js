require("./loadEnv");
const fs = require("fs");
const path = require("path");
const express = require("express");
const cors = require("cors");
const { connectDB } = require("./db");
const { getGenreProfile, listGenres } = require("./genreProfiles");
const { parsePromptPreferences } = require("./promptPreferences");
const { parsePromptWithGemini } = require("./geminiPromptParser");

const app = express();
const PORT = Number(process.env.PORT) || 3000;

app.use(cors());
app.use(express.json());

let seedsCollection;
let seedsDataset;
let initPromise;
let listenPromise;

function humanizeBiome(value) {
  return String(value || "")
    .split("_")
    .filter(Boolean)
    .map(word => word.charAt(0).toUpperCase() + word.slice(1))
    .join(" ");
}

function ensureReady(res) {
  if (seedsCollection || seedsDataset) {
    return true;
  }

  res.status(503).json({ error: "mc-seed-db is still starting up." });
  return false;
}

function loadSeedsFromFile() {
  const filePath = path.join(__dirname, "seeds.json");
  return JSON.parse(fs.readFileSync(filePath, "utf8"));
}

function matchesQuery(doc, query) {
  return Object.entries(query).every(([key, value]) => {
    if (key === "$or" && Array.isArray(value)) {
      return value.some(condition => matchesQuery(doc, condition));
    }

    return doc[key] === value;
  });
}

async function findSeeds(query = {}, limit = 0) {
  if (seedsCollection) {
    let cursor = seedsCollection.find(query);
    if (limit > 0) {
      cursor = cursor.limit(limit);
    }
    return cursor.toArray();
  }

  const matches = (seedsDataset || []).filter(doc => matchesQuery(doc, query));
  return limit > 0 ? matches.slice(0, limit) : matches;
}

function addProfileFields(fields, profile) {
  if (!profile) {
    return;
  }

  for (const biome of profile.biomes || []) {
    fields.add(biome.field);
  }

  for (const structure of profile.structures || []) {
    fields.add(structure.field);
  }
}

function buildSeedQuery(profile, preferences = {}) {
  const query = {};
  const preferredFields = new Set();

  addProfileFields(preferredFields, profile);

  // Spawn biome filter — hard constraint from prompt
  if (preferences.spawnBiome) {
    query.spawnBiome = preferences.spawnBiome;
  }

  for (const requirement of preferences.required || []) {
    query[requirement.field] = true;
    preferredFields.add(requirement.field);
  }

  for (const exclusion of preferences.excluded || []) {
    query[exclusion.field] = false;
  }

  if (preferredFields.size) {
    query.$or = [...preferredFields].map(field => ({ [field]: true }));
  }

  return query;
}

function scoreSeed(doc, profile, preferences = {}, options = {}) {
  const relaxedPromptMatching = Boolean(options.relaxedPromptMatching);
  let score = 0;
  const matchedBiomes = [];
  const matchedStructures = [];
  const matchedPromptFeatures = [];
  const avoidedPromptFeatures = [];
  const missingPromptFeatures = [];

  for (const biome of profile.biomes || []) {
    if (doc[biome.field]) {
      score += biome.weight;
      matchedBiomes.push(biome.label);
    }
  }

  for (const structure of profile.structures || []) {
    if (doc[structure.field]) {
      score += structure.weight;
      matchedStructures.push(structure.label);
    }
  }

  for (const requirement of preferences.required || []) {
    if (doc[requirement.field]) {
      score += 3.2;
      matchedPromptFeatures.push(requirement.label);
    } else if (relaxedPromptMatching) {
      score -= 4;
      missingPromptFeatures.push(requirement.label);
    }
  }

  for (const exclusion of preferences.excluded || []) {
    if (!doc[exclusion.field]) {
      score += 0.8;
      avoidedPromptFeatures.push(exclusion.label);
    } else if (relaxedPromptMatching) {
      score -= 2.4;
      missingPromptFeatures.push(`No ${exclusion.label}`);
    }
  }

  return {
    score,
    matchedBiomes,
    matchedStructures,
    matchedPromptFeatures,
    avoidedPromptFeatures,
    missingPromptFeatures
  };
}

function pickSeed(candidates, profile, preferences = {}, options = {}) {
  const scored = candidates
    .map(doc => ({
      doc,
      ...scoreSeed(doc, profile, preferences, options)
    }))
    .filter(candidate => candidate.score > 0)
    .sort((left, right) => right.score - left.score);

  if (!scored.length) {
    return null;
  }

  const topPool = scored.slice(0, Math.min(12, scored.length));
  return topPool[Math.floor(Math.random() * topPool.length)];
}

async function initializeData() {
  if (initPromise) {
    return initPromise;
  }

  initPromise = (async () => {
    try {
      const db = await connectDB();
      seedsCollection = db.collection("seeds");
      console.log("Connected to MongoDB for mc-seed-db.");
    } catch (error) {
      seedsDataset = loadSeedsFromFile();
      console.warn("MongoDB unavailable, using local seeds.json fallback.");
      console.warn(error.message);
    }
  })();

  return initPromise;
}

async function startServer(options = {}) {
  const { listen = true } = options;

  await initializeData();

  if (!listen) {
    return;
  }

  if (listenPromise) {
    return listenPromise;
  }

  listenPromise = new Promise(resolve => {
    app.listen(PORT, () => {
      console.log(`API running on http://localhost:${PORT}`);
      resolve();
    });
  });

  return listenPromise;
}

if (require.main === module) {
  startServer().catch(error => {
    console.error("Failed to start mc-seed-db:", error);
    process.exit(1);
  });
}

app.get("/api/health", (req, res) => {
  res.json({
    ok: Boolean(seedsCollection || seedsDataset),
    source: seedsCollection ? "mongodb" : seedsDataset ? "local" : "starting"
  });
});

app.get("/api/genres", (req, res) => {
  res.json({ genres: listGenres() });
});

app.get("/api/recommend-seed", async (req, res) => {
  if (!ensureReady(res)) {
    return;
  }

  const selectedGenre = String(req.query.genre || "").trim();
  const prompt = String(req.query.prompt || "").trim();

  // --- Parse prompt: try Gemini first, fall back to local regex parser ---
  let promptPreferences = null;
  let promptSource = "none";

  if (prompt) {
    try {
      promptPreferences = await parsePromptWithGemini(prompt);
      if (promptPreferences) {
        promptSource = "gemini";
        console.log("Gemini parsed prompt:", JSON.stringify(promptPreferences, null, 2));
      }
    } catch (err) {
      console.warn("Gemini prompt parse failed, falling back to regex:", err.message);
    }

    if (!promptPreferences) {
      promptPreferences = parsePromptPreferences(prompt);
      promptSource = "regex";
      console.log("Regex parsed prompt:", JSON.stringify(promptPreferences, null, 2));
    }
  } else {
    promptPreferences = { text: "", genreKey: null, spawnBiome: null, required: [], excluded: [], hasPreferences: false };
  }

  let selectedProfile = null;
  if (selectedGenre) {
    selectedProfile = getGenreProfile(selectedGenre);
  }

  if (selectedGenre && !selectedProfile) {
    return res.status(400).json({
      error: "Unknown genre. Try ambient, country, electronic, indie pop, jazz, metal, or classic Minecraft."
    });
  }

  const promptProfile = promptPreferences.genreKey ? getGenreProfile(promptPreferences.genreKey) : null;
  const profile = promptProfile || selectedProfile || getGenreProfile("default");

  try {
    const exactCandidates = await findSeeds(buildSeedQuery(profile, promptPreferences));
    let candidates = exactCandidates;
    let picked = pickSeed(candidates, profile, promptPreferences);
    let matchMode = "exact";

    // Relaxed fallback: drop spawnBiome constraint if no exact match
    if (!picked && promptPreferences.hasPreferences) {
      const relaxedPreferences = { ...promptPreferences, spawnBiome: null };
      candidates = await findSeeds(buildSeedQuery(profile, relaxedPreferences));
      picked = pickSeed(candidates, profile, promptPreferences, {
        relaxedPromptMatching: true
      });
      matchMode = "closest";
    }

    // Further fallback: drop all prompt constraints
    if (!picked && promptPreferences.hasPreferences) {
      candidates = await findSeeds(buildSeedQuery(profile));
      picked = pickSeed(candidates, profile, promptPreferences, {
        relaxedPromptMatching: true
      });
      matchMode = "genre-only";
    }

    if (!picked) {
      return res.status(404).json({
        error: promptPreferences.hasPreferences
          ? `No indexed seeds matched ${profile.label.toLowerCase()} with those nearby requirements yet.`
          : `No indexed seeds matched ${profile.label.toLowerCase()} yet.`
      });
    }

    const {
      doc,
      matchedBiomes,
      matchedStructures,
      matchedPromptFeatures,
      avoidedPromptFeatures,
      missingPromptFeatures,
      score
    } = picked;

    res.json({
      genre: {
        key: profile.key,
        label: profile.label,
        description: profile.description,
        biomes: profile.biomes.map(({ label }) => label),
        note: profile.note || null
      },
      seed: doc.seed,
      spawnBiome: humanizeBiome(doc.spawnBiome),
      matchedBiomes,
      matchedStructures,
      matchedPromptFeatures,
      avoidedPromptFeatures,
      missingPromptFeatures,
      matchScore: Number(score.toFixed(2)),
      matchMode,
      totalMatchingSeeds: candidates.length,
      exactPromptMatchCount: exactCandidates.length,
      prompt: {
        text: promptPreferences.text || null,
        source: promptSource,
        inferredGenre: promptProfile ? promptProfile.label : null,
        overridesSelectedGenre: Boolean(promptProfile && selectedProfile && promptProfile.key !== selectedProfile.key),
        requestedSpawnBiome: promptPreferences.spawnBiome ? humanizeBiome(promptPreferences.spawnBiome) : null,
        required: promptPreferences.required.map(({ label }) => label),
        excluded: promptPreferences.excluded.map(({ label }) => label)
      },
      source: seedsCollection ? "mc-seed-db (mongodb)" : "mc-seed-db (local seeds.json fallback)"
    });
  } catch (error) {
    console.error("Genre lookup failed:", error);
    res.status(500).json({ error: "Could not fetch a seed from mc-seed-db." });
  }
});

app.get("/seeds", async (req, res) => {
  if (!ensureReady(res)) {
    return;
  }

  const seeds = await findSeeds({}, 20);
  res.json(seeds);
});

app.get("/search", async (req, res) => {
  if (!ensureReady(res)) {
    return;
  }

  const { village, cherry, mansion } = req.query;

  const query = {};

  if (village === "true") query.villageCloseBy = true;
  if (cherry === "true") query.cherryGroveCloseBy = true;
  if (mansion === "true") query.mansionCloseBy = true;

  const results = await findSeeds(query, 20);

  res.json(results);
});

module.exports = { app, startServer };
