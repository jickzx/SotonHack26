require("dotenv").config();
const fs = require("fs");
const { connectDB } = require("./db");

async function main() {
  const db = await connectDB();
  const collection = db.collection("seeds");

  const raw = fs.readFileSync("./seeds.json", "utf8");
  const docs = JSON.parse(raw);

  const docsWithTimestamp = docs.map(doc => ({
    ...doc,
    createdAt: new Date()
  }));

  try {
    const result = await collection.insertMany(docsWithTimestamp, { ordered: false });
    console.log("Inserted documents:", result.insertedCount);
  } catch (error) {
    console.log("Some documents may already exist.");
    if (error.writeErrors) {
      console.log("Duplicate count:", error.writeErrors.length);
    } else {
      console.error(error);
    }
  }
}

main().catch(console.error);