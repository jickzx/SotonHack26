const { app, startServer } = require("../mc-seed-db/server");

let readyPromise;

function ensureStarted() {
  if (!readyPromise) {
    readyPromise = startServer({ listen: false });
  }

  return readyPromise;
}

function buildHandler(pathname) {
  return async (req, res) => {
    await ensureStarted();

    const queryIndex = req.url.indexOf("?");
    const query = queryIndex >= 0 ? req.url.slice(queryIndex) : "";
    req.url = `${pathname}${query}`;

    return app(req, res);
  };
}

module.exports = { buildHandler };
