require("./loadEnv");
const { connectDB } = require("./db");

async function main() {
  const db = await connectDB();
  const collection = db.collection("seeds");

  const docs = await collection.find({}).toArray();

  console.log("All seed documents:");
  console.log(JSON.stringify(docs, null, 2));
}

main().catch(console.error);
