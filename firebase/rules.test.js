import { readFileSync } from "node:fs";
import { afterAll, beforeAll, beforeEach, describe, test } from "vitest";
import { assertFails, assertSucceeds, initializeTestEnvironment } from "@firebase/rules-unit-testing";
import { collection, deleteDoc, doc, getDoc, getDocs, serverTimestamp, setDoc, writeBatch } from "firebase/firestore";

let testEnv;

beforeAll(async () => {
  testEnv = await initializeTestEnvironment({
    projectId: "demo-foodsense",
    firestore: { rules: readFileSync("firestore.rules", "utf8") },
  });
});

afterAll(() => testEnv.cleanup());
beforeEach(() => testEnv.clearFirestore());

const productsOf = (db, uid) => collection(db, "users", uid, "products");

describe("users/{userId}/products", () => {
  test("owner_ownProduct_canWriteAndRead", async () => {
    const alice = testEnv.authenticatedContext("alice").firestore();
    const ref = doc(productsOf(alice, "alice"), "p1");

    await assertSucceeds(setDoc(ref, { name: "Leche", updatedAt: 1, items: [] }));
    await assertSucceeds(getDoc(ref));
  });

  test("owner_ownCollection_canList", async () => {
    const alice = testEnv.authenticatedContext("alice").firestore();

    await assertSucceeds(getDocs(productsOf(alice, "alice")));
  });

  test("otherUser_someoneElsesProducts_cannotRead", async () => {
    await testEnv.withSecurityRulesDisabled(async (ctx) => {
      await setDoc(doc(ctx.firestore(), "users/alice/products/p1"), { name: "Leche" });
    });
    const bob = testEnv.authenticatedContext("bob").firestore();

    await assertFails(getDoc(doc(productsOf(bob, "alice"), "p1")));
    await assertFails(getDocs(productsOf(bob, "alice")));
  });

  test("otherUser_someoneElsesProducts_cannotWriteOrDelete", async () => {
    const bob = testEnv.authenticatedContext("bob").firestore();

    await assertFails(setDoc(doc(productsOf(bob, "alice"), "p1"), { name: "hack" }));
    await assertFails(deleteDoc(doc(productsOf(bob, "alice"), "p1")));
  });

  test("unauthenticated_anyProducts_isDenied", async () => {
    const anon = testEnv.unauthenticatedContext().firestore();

    await assertFails(getDocs(productsOf(anon, "alice")));
    await assertFails(setDoc(doc(productsOf(anon, "alice"), "p1"), { name: "hack" }));
  });
});

const v2Ref = (db, uid, path, id) => doc(db, "users", uid, path, id);
const effect = (id, revision = 1, deleted = false, type = "PRODUCT") => ({
  type, id, revision, deleted, written: true,
});
const metadata = (operationId, revision = 1, deleted = false) => ({
  protocolVersion: 1, operationId, revision, deleted, updatedAt: serverTimestamp(),
});
const receipt = (effects) => ({
  protocolVersion: 1, fingerprint: "a".repeat(64), committedAt: serverTimestamp(), effects,
});

function writeProduct(db, uid, operationId, revision = 1, deleted = false, overrides = {}) {
  const batch = writeBatch(db);
  batch.set(v2Ref(db, uid, "inventory_products", "p1"), {
    name: "Apples", ...metadata(operationId, revision, deleted), ...overrides,
  });
  batch.set(v2Ref(db, uid, "inventory_operations", operationId), receipt({
    "PRODUCT:p1": effect("p1", revision, deleted),
  }));
  return batch.commit();
}

describe("versioned inventory protocol", () => {
  test("owner_atomicEntityAndReceipt_canCreateAndAdvanceRevision", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(writeProduct(db, "alice", "create"));
    await assertSucceeds(writeProduct(db, "alice", "rename", 2));
    await assertSucceeds(getDoc(v2Ref(db, "alice", "inventory_operations", "rename")));
  });

  test("entity_withoutAtomicReceipt_isDenied", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertFails(setDoc(v2Ref(db, "alice", "inventory_products", "p1"), {
      name: "Apples", ...metadata("missing"),
    }));
  });

  test("revision_skippedOrReused_isDenied", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(writeProduct(db, "alice", "create"));
    await assertFails(writeProduct(db, "alice", "skip", 3));
    await assertFails(writeProduct(db, "alice", "same", 1));
  });

  test("receipt_cannotBeModifiedOrDeleted", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(writeProduct(db, "alice", "create"));
    const ref = v2Ref(db, "alice", "inventory_operations", "create");
    await assertFails(setDoc(ref, receipt({})));
    await assertFails(deleteDoc(ref));
  });

  test("receipt_preexisting_cannotAuthorizeALaterWrite", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(setDoc(v2Ref(db, "alice", "inventory_operations", "precreated"), receipt({
      "PRODUCT:p1": effect("p1"),
    })));
    await assertFails(setDoc(v2Ref(db, "alice", "inventory_products", "p1"), {
      name: "Apples", ...metadata("precreated"),
    }));
  });

  test("tombstone_cannotBeResurrectedOrPhysicallyDeleted", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    await assertSucceeds(writeProduct(db, "alice", "create"));
    await assertSucceeds(writeProduct(db, "alice", "delete", 2, true));
    await assertFails(writeProduct(db, "alice", "resurrect", 3));
    await assertFails(deleteDoc(v2Ref(db, "alice", "inventory_products", "p1")));
  });

  test("otherAccountAndAnonymous_cannotReadOrWriteV2Collections", async () => {
    const bob = testEnv.authenticatedContext("bob").firestore();
    const anon = testEnv.unauthenticatedContext().firestore();
    for (const db of [bob, anon]) {
      await assertFails(writeProduct(db, "alice", "op"));
      for (const path of ["inventory_products", "inventory_items", "inventory_operations"]) {
        await assertFails(getDocs(collection(db, "users", "alice", path)));
      }
    }
  });

  test("batchOfFifteenItems_passesRuleAccessLimits", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    const batch = writeBatch(db);
    const effects = {};
    for (let i = 0; i < 15; i++) {
      const id = `i${i}`;
      effects[`ITEM:${id}`] = effect(id, 1, false, "ITEM");
      batch.set(v2Ref(db, "alice", "inventory_items", id), {
        productId: "p1", quantity: 1, unit: null, expirationDate: null, addedAt: 20000,
        ...metadata("batch15"),
      });
    }
    batch.set(v2Ref(db, "alice", "inventory_operations", "batch15"), receipt(effects));
    await assertSucceeds(batch.commit());
  });

  test("invalidItem_abortsWholeBatchIncludingReceipt", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    const batch = writeBatch(db);
    batch.set(v2Ref(db, "alice", "inventory_items", "bad"), {
      productId: "p1", quantity: -1, unit: null, expirationDate: null, addedAt: 20000,
      ...metadata("bad-item"),
    });
    batch.set(v2Ref(db, "alice", "inventory_operations", "bad-item"), receipt({
      "ITEM:bad": effect("bad", 1, false, "ITEM"),
    }));
    await assertFails(batch.commit());
    const receiptDoc = await getDoc(v2Ref(db, "alice", "inventory_operations", "bad-item"));
    if (receiptDoc.exists()) throw new Error("Denied batch left a receipt behind");
  });

  test("receiptWithDifferentEffect_cannotAuthorizeAnEntityWrite", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    const batch = writeBatch(db);
    batch.set(v2Ref(db, "alice", "inventory_products", "p1"), {
      name: "Apples", ...metadata("wrong-effect"),
    });
    batch.set(v2Ref(db, "alice", "inventory_operations", "wrong-effect"), receipt({
      "PRODUCT:p1": effect("p1", 2),
    }));
    await assertFails(batch.commit());
  });

  test("maximumAtomicAction_passesRuleAccessLimits", async () => {
    const db = testEnv.authenticatedContext("alice").firestore();
    const batch = writeBatch(db);
    const effects = {};
    for (let i = 0; i < 100; i++) {
      const id = `i${i}`;
      effects[`ITEM:${id}`] = effect(id, 1, true, "ITEM");
      batch.set(v2Ref(db, "alice", "inventory_items", id), metadata("max-action", 1, true));
    }
    batch.set(v2Ref(db, "alice", "inventory_operations", "max-action"), receipt(effects));
    await assertSucceeds(batch.commit());
  });
});
