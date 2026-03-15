require("./loadEnv");
const express = require("express");
const cors = require("cors");
const { connectDB } = require("./db");

const app = express();
app.use(cors());
app.use(express.json());

let seedsCollection;

async function startServer() {
  const db = await connectDB();
  seedsCollection = db.collection("seeds");

  app.listen(3000, () => {
    console.log("API running on http://localhost:3000");
  });
}

startServer();

app.get("/seeds", async (req, res) => {
  const seeds = await seedsCollection.find({}).limit(20).toArray();
  res.json(seeds);
});

app.get("/search", async (req, res) => {
  const { village, cherry, mansion } = req.query;

  const query = {};

  if (village === "true") query.villageCloseBy = true;
  if (cherry === "true") query.cherryGroveCloseBy = true;
  if (mansion === "true") query.mansionCloseBy = true;

  const results = await seedsCollection.find(query).limit(20).toArray();

  res.json(results);
});
