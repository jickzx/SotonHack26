const { startServer } = require("./mc-seed-db/server");

startServer().catch(error => {
  console.error("Failed to start sotonhack26 server:", error);
  process.exit(1);
});
