require("./loadEnv");
const { connectDB } = require("./db");

async function main() {
  const db = await connectDB();
  const collection = db.collection("seeds");

  const count = await collection.countDocuments();
  console.log("Total seeds in database:", count);
}

main().catch(console.error);
