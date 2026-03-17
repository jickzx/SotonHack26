const GENRE_PROFILES = {
  ambient: {
    label: "Ambient",
    description: "Quiet, misty worlds with dark forests, swamps, and strange shoreline pockets.",
    biomes: [
      { field: "darkForestCloseBy", label: "Dark Forest", weight: 3.0 },
      { field: "swampCloseBy", label: "Swamp", weight: 2.6 },
      { field: "mangroveSwampCloseBy", label: "Mangrove Swamp", weight: 2.4 },
      { field: "mushroomFieldsCloseBy", label: "Mushroom Fields", weight: 2.1 }
    ],
    structures: [
      { field: "strongholdCloseBy", label: "Stronghold", weight: 1.0 },
      { field: "ruinedPortalCloseBy", label: "Ruined Portal", weight: 0.7 }
    ],
    note: "Built from the atmospheric overworld biomes currently indexed in mc-seed-db."
  },
  country: {
    label: "Country",
    description: "Open mountain-country seeds with meadows, snowy slopes, and big horizon energy.",
    biomes: [
      { field: "meadowCloseBy", label: "Meadow", weight: 2.7 },
      { field: "snowySlopesCloseBy", label: "Snowy Slopes", weight: 2.8 },
      { field: "jaggedPeaksCloseBy", label: "Jagged Peaks", weight: 2.4 },
      { field: "frozenPeaksCloseBy", label: "Frozen Peaks", weight: 2.1 },
      { field: "stonyPeaksCloseBy", label: "Stony Peaks", weight: 2.1 }
    ],
    structures: [
      { field: "villageCloseBy", label: "Village", weight: 1.1 },
      { field: "iglooCloseBy", label: "Igloo", weight: 0.6 }
    ]
  },
  electronic: {
    label: "Electronic",
    description: "Bright, dry, high-contrast terrain with badlands, deserts, and fast savanna runs.",
    biomes: [
      { field: "badlandsCloseBy", label: "Badlands", weight: 3.0 },
      { field: "woodedBadlandsCloseBy", label: "Wooded Badlands", weight: 2.5 },
      { field: "desertCloseBy", label: "Desert", weight: 2.5 },
      { field: "savannaCloseBy", label: "Savanna", weight: 2.3 },
      { field: "savannaPlateauCloseBy", label: "Savanna Plateau", weight: 2.0 }
    ],
    structures: [
      { field: "ruinedPortalCloseBy", label: "Ruined Portal", weight: 1.0 },
      { field: "pillagerOutpostCloseBy", label: "Pillager Outpost", weight: 0.8 }
    ]
  },
  indipop: {
    label: "Indie Pop",
    description: "Soft, bright seeds with cherry groves, flower forests, and gentler overworld starts.",
    biomes: [
      { field: "cherryGroveCloseBy", label: "Cherry Grove", weight: 3.0 },
      { field: "flowerForestCloseBy", label: "Flower Forest", weight: 2.6 },
      { field: "meadowCloseBy", label: "Meadow", weight: 2.2 },
      { field: "forestCloseBy", label: "Forest", weight: 1.6 }
    ],
    structures: [
      { field: "villageCloseBy", label: "Village", weight: 0.8 },
      { field: "strongholdCloseBy", label: "Stronghold", weight: 0.4 }
    ]
  },
  jazz: {
    label: "Jazz",
    description: "Playful seeds with meadows, flower forests, jungles, and warm coastal detours.",
    biomes: [
      { field: "meadowCloseBy", label: "Meadow", weight: 2.6 },
      { field: "flowerForestCloseBy", label: "Flower Forest", weight: 2.4 },
      { field: "jungleCloseBy", label: "Jungle", weight: 2.5 },
      { field: "sparseJungleCloseBy", label: "Sparse Jungle", weight: 2.0 },
      { field: "bambooJungleCloseBy", label: "Bamboo Jungle", weight: 2.2 },
      { field: "beachCloseBy", label: "Beach", weight: 1.4 }
    ],
    structures: [
      { field: "jungleTempleCloseBy", label: "Jungle Temple", weight: 1.0 },
      { field: "villageCloseBy", label: "Village", weight: 0.7 }
    ]
  },
  metal: {
    label: "Metal",
    description: "Harsh, dramatic terrain built from the closest overworld matches to a heavier sound.",
    biomes: [
      { field: "jaggedPeaksCloseBy", label: "Jagged Peaks", weight: 2.8 },
      { field: "frozenPeaksCloseBy", label: "Frozen Peaks", weight: 2.5 },
      { field: "stonyPeaksCloseBy", label: "Stony Peaks", weight: 2.4 },
      { field: "darkForestCloseBy", label: "Dark Forest", weight: 2.0 }
    ],
    structures: [
      { field: "pillagerOutpostCloseBy", label: "Pillager Outpost", weight: 1.0 },
      { field: "mansionCloseBy", label: "Woodland Mansion", weight: 1.0 },
      { field: "ruinedPortalCloseBy", label: "Ruined Portal", weight: 0.8 }
    ],
    note: "The current seed database does not index Nether or Deep Dark terrain yet, so this uses the closest dramatic overworld matches instead."
  },
  default: {
    label: "Classic Minecraft",
    description: "Easygoing seeds with plains, forests, taiga, rivers, and a familiar survival feel.",
    biomes: [
      { field: "plainsCloseBy", label: "Plains", weight: 2.5 },
      { field: "forestCloseBy", label: "Forest", weight: 2.4 },
      { field: "taigaCloseBy", label: "Taiga", weight: 2.2 },
      { field: "riverCloseBy", label: "River", weight: 1.8 },
      { field: "oceanCloseBy", label: "Ocean", weight: 1.5 }
    ],
    structures: [
      { field: "villageCloseBy", label: "Village", weight: 1.0 },
      { field: "strongholdCloseBy", label: "Stronghold", weight: 0.8 },
      { field: "ruinedPortalCloseBy", label: "Ruined Portal", weight: 0.7 }
    ]
  }
};

const GENRE_ALIASES = {
  ambient: "ambient",
  country: "country",
  electronic: "electronic",
  electronica: "electronic",
  indipop: "indipop",
  indiepop: "indipop",
  indie: "indipop",
  jazz: "jazz",
  metal: "metal",
  default: "default",
  classicminecraft: "default",
  classic: "default"
};

function normalizeGenreKey(value = "") {
  return value.toLowerCase().replace(/[^a-z]/g, "");
}

function resolveGenreKey(value) {
  return GENRE_ALIASES[normalizeGenreKey(value)] || null;
}

function findGenreKeyInText(value = "") {
  const normalized = normalizeGenreKey(value);
  const aliases = Object.keys(GENRE_ALIASES).sort((left, right) => right.length - left.length);

  for (const alias of aliases) {
    if (normalized.includes(alias)) {
      return GENRE_ALIASES[alias];
    }
  }

  return null;
}

function serializeProfile(key, profile) {
  return {
    key,
    label: profile.label,
    description: profile.description,
    biomes: profile.biomes.map(({ label }) => label),
    note: profile.note || null
  };
}

function listGenres() {
  return Object.entries(GENRE_PROFILES).map(([key, profile]) => serializeProfile(key, profile));
}

function getGenreProfile(value) {
  const key = resolveGenreKey(value);
  if (!key) {
    return null;
  }

  return {
    key,
    ...GENRE_PROFILES[key]
  };
}

module.exports = {
  GENRE_PROFILES,
  findGenreKeyInText,
  getGenreProfile,
  listGenres,
  resolveGenreKey
};
