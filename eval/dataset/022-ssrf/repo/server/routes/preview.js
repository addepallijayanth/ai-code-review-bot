const express = require("express");

const router = express.Router();

function extractTitle(html) {
  const match = html.match(/<title[^>]*>([^<]*)<\/title>/i);
  return match ? match[1].trim() : null;
}

router.get("/preview", async (req, res) => {
  const target = req.query.url;
  if (!target) {
    return res.status(400).json({ error: "url is required" });
  }
  try {
    const response = await fetch(target);
    const html = await response.text();
    res.json({ url: target, title: extractTitle(html) });
  } catch (err) {
    res.status(502).json({ error: "could not fetch the page" });
  }
});

module.exports = router;
