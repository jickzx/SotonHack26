const { MongoClient } = require("mongodb");

let client;
let db;

async function connectDB() {
  if (db) return db;

  client = new MongoClient(process.env.MONGODB_URI, {
    serverSelectionTimeoutMS: 3000,
    connectTimeoutMS: 3000,
    socketTimeoutMS: 3000
  });
  await client.connect();
  db = client.db("mcseedapp");
  return db;
}

module.exports = { connectDB };
