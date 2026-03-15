require("dotenv").config();
const { connectDB } = require("./db");

async function main() {
  const db = await connectDB();
  const collection = db.collection("seeds");

  await collection.createIndex({ seed: 1 }, { unique: true });

  console.log("Unique index created on seed");
}

main().catch(console.error);