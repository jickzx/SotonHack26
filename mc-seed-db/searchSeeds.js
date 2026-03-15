require("dotenv").config();
const { connectDB } = require("./db");

async function main() {
  const db = await connectDB();
  const collection = db.collection("seeds");

  const results = await collection.find({
    villageCloseBy: true,
    ruinedPortalCloseBy: true
  }).toArray();

  console.log(results);
}

main().catch(console.error);