import { Router } from "express";
import { parseCsv } from "../csv";
import { importContacts } from "../services/importService";
import { contactRepository } from "../repositories/contactRepository";

export const contacts = Router();

contacts.post("/contacts/import", async (req, res) => {
  const rows = parseCsv(req.body);
  const imported = await importContacts(rows, contactRepository);
  res.json({ imported });
});
