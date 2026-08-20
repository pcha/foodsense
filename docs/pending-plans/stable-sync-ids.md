# Ids de sincronización generados en el cliente

**Estado:** propuesta, no implementada.
**Requiere:** migración de Room a schema v8.

## Resumen

Hoy el id que identifica un producto o un ítem en Firestore lo **genera el servidor**, y Room lo
aprende después. Entre esos dos momentos la fila local no tiene forma de reconocerse a sí misma en
un snapshot, y con la cola offline de Firestore esa ventana puede durar indefinidamente.

De ahí salen dos síntomas opuestos, y no se pueden arreglar los dos a la vez con el modelo actual:

- Si al aplicar el remoto se conservan los ítems sin id → **duplicados**.
- Si se descartan → se **pierde** lo cargado sin conexión.

La propuesta es invertir quién genera el id: que lo asigne Room al crear la fila, y que Firestore lo
reciba ya hecho. Con eso la identidad deja de ser ambigua y los dos síntomas desaparecen.

## El problema

### Qué se intentó y se revirtió

Se intentó conservar los ítems con `serverId == null` al aplicar el remoto, asumiendo que ese campo
en null significaba "este ítem nunca llegó a Firestore". Duplicaba productos e ítems y **se
revirtió**. Un segundo intento —hacer que todos los caminos de push escribieran el id de vuelta en
Room— tampoco alcanzó, porque no ataca el caso offline.

### La ambigüedad de fondo

`ItemEntity.serverId == null` no distingue dos situaciones que exigen respuestas opuestas:

| Situación | Qué corresponde hacer | Cómo se ve en Room |
|---|---|---|
| El ítem nunca llegó al servidor | conservarlo | `serverId == null` |
| El ítem llegó por la cola offline, pero el ack nunca volvió | dejar que el remoto lo reemplace | `serverId == null` |

Son indistinguibles porque **Firestore acepta la escritura de forma durable mucho antes de que Room
pueda aprender su id**. `SyncModule` habilita `persistentCacheSettings`, así que un write sin
conexión se encola en disco y sobrevive al cierre de la app, pero el `.await()` del repositorio no
retorna hasta que el servidor confirma. La corrutina queda suspendida y `updateServerId` nunca corre.

## Diagramas de secuencia

### 1. Hoy — se pierde lo cargado sin conexión (comportamiento vigente)

```
Room                          Firestore (cola local)        Servidor
 │                                     │                        │
 │ add("Leche")                        │                        │
 │  producto serverId=null             │                        │
 │  ítem     serverId=null             │                        │
 │                                     │                        │
 │ upsertProduct() ───────────────────►│  write encolado        │
 │   .await() suspende ⏳              │  (en disco)            │
 │                                     │                        │
 │   [ sin conexión ]                  │                        │
 │                                     │                        │
 │◄── el snapshot llega vacío ─────────┤                        │
 │    applyRemoteChanges([])           │                        │
 │      → no hay doc que matchear      │                        │
 │      → la fila local sobrevive      │                        │
 │        (todavía no tiene serverId)  │                        │
 │                                     │                        │
 │   [ vuelve la conexión ]            │                        │
 │                                     ├──── se vacía ─────────►│  doc A creado
 │                                     │                        │  ítem con UUID-1
 │◄─────────── snapshot: doc A ────────┴────────────────────────┤
 │                                     │                        │
 │ applyRemoteChanges([doc A])         │                        │
 │   match por nombre → adopta doc A   │                        │
 │   borra TODOS los ítems locales     │                        │
 │   inserta los del remoto            │                        │
 │                                     │                        │
 └─► correcto acá, pero si en la ventana ⏳ se hubiera cargado otro
     ítem que no llegó a encolarse, se pierde sin aviso
```

### 2. El intento revertido — duplicaba

```
Room                                                    Servidor
 │ ítem cargado offline, serverId=null                      │
 │ (pero la cola de Firestore YA lo entregó)                │
 │                                                          │
 │◄────────── snapshot: doc A, ítem UUID-1 ─────────────────┤
 │                                                          │
 │ applyRemoteChanges                                       │
 │   borra sólo los ítems con serverId != null              │
 │     └─ el ítem local sobrevive  (serverId == null)       │
 │   inserta los ítems del remoto                           │
 │     └─ entra UUID-1, que es EL MISMO ítem                │
 │                                                          │
 └─► dos filas para un solo ítem  ✗
```

El fix leía `serverId == null` como "es local", pero el ítem ya estaba en el servidor bajo un id que
Room nunca llegó a conocer.

### 3. Con ids estables — se resuelve

```
Room                          Firestore (cola local)        Servidor
 │                                     │                        │
 │ add("Leche")                        │                        │
 │  producto syncId = P-abc  ◄── generado acá, no en el servidor│
 │  ítem     syncId = I-xyz            │                        │
 │           pendingPush = true        │                        │
 │                                     │                        │
 │ set(doc P-abc) ────────────────────►│  write encolado        │
 │   .await() suspende ⏳              │                        │
 │   [ sin conexión ]                  │                        │
 │                                     │                        │
 │   [ vuelve la conexión ]            │                        │
 │                                     ├──── se vacía ─────────►│  doc P-abc
 │                                     │                        │  ítem I-xyz
 │◄─────────── snapshot: P-abc ────────┴────────────────────────┤
 │                                     │                        │
 │ applyRemoteChanges                  │                        │
 │   match por syncId → es MI producto │                        │
 │   el ítem I-xyz viene en el remoto: │                        │
 │     → es el mío, confirmado         │                        │
 │     → pendingPush = false           │                        │
 │     → no se inserta una copia       │                        │
 │                                     │                        │
 └─► un producto, un ítem  ✓
```

Y el caso que #1 y #2 querían proteger, ahora sin ambigüedad:

```
 │ ítem I-999, pendingPush = true                            │
 │◄──────── snapshot: doc P-abc SIN el ítem I-999 ───────────┤
 │                                                           │
 │ applyRemoteChanges                                        │
 │   I-999 no está en el remoto Y sigue pendingPush          │
 │     → nunca llegó al servidor → se conserva  ✓            │
 │                                                           │
 │ ítem I-111, pendingPush = false (confirmado antes)        │
 │   no está en el remoto → lo borraron en otro dispositivo  │
 │     → se borra acá también  ✓                             │
```

## La propuesta

### 1. El id lo genera Room

`ProductEntity` e `ItemEntity` pasan a tener un `syncId: String` **no nulo**, asignado con
`UUID.randomUUID()` en el momento de insertar la fila. Reemplaza al `serverId: String?` actual.

`FirebaseFirestoreSyncRepository` deja de usar `collection.add(...)` —que crea un doc nuevo en cada
llamada— y pasa a `collection.document(syncId).set(data, merge)`, que es **idempotente**. Eso sólo
ya elimina los dos docs que hoy puede generar un add offline (uno por la cola de Firestore, otro por
`migrateLocalDataToFirestore` al no ver `serverId`).

### 2. Un flag de pendiente

Los ids estables resuelven la **identidad**, pero por sí solos no distinguen "todavía no lo pusheé"
de "lo borraron allá": en los dos casos el id local no aparece en el snapshot.

Por eso hace falta además `pendingPush: Boolean` en `ItemEntity`:

- se pone en `true` en cada escritura local;
- se pone en `false` cuando el ítem **aparece en un snapshot** — verlo con el id propio es la
  confirmación de que el servidor lo tiene, y funciona incluso si el `.await()` nunca volvió;
- en `applyRemoteChanges`, un ítem ausente del remoto se conserva si `pendingPush`, y se borra si no.

Ese segundo punto es lo que cierra el círculo, y es justamente lo que no se podía hacer con ids
asignados por el servidor.

### 3. Migración a schema v8

`syncId` tiene que preservar el id de Firestore que ya tienen las filas sincronizadas, o todo se
vuelve a subir como si fuera nuevo:

```sql
-- product / item
ALTER TABLE product ADD COLUMN syncId TEXT NOT NULL DEFAULT '';
UPDATE product SET syncId = COALESCE(serverId, lower(hex(randomblob(16))));
-- ídem para item, más:
ALTER TABLE item ADD COLUMN pendingPush INTEGER NOT NULL DEFAULT 0;
```

Los ítems existentes con `serverId == null` quedan con `pendingPush = 0` a propósito: hoy no se sabe
si están en el servidor, y asumir que sí es lo que ya hace el comportamiento actual.

Índice único sobre `syncId` en las dos tablas.

## Qué se puede simplificar después

- **El merge por nombre** de `applyRemoteChanges` deja de ser necesario como reparación de
  duplicados. Conviene conservarlo igual para el caso legítimo —dos dispositivos que crean "Leche"
  por separado antes del primer sync son productos distintos con el mismo nombre— pero deja de ser
  la red de contención que es hoy.
- **`persistItemServerIds`** desaparece: no hay nada que traer de vuelta.

## Costos y riesgos

- Migración de Room con backfill; hay `MigrationTest` para cubrirla.
- Toca `ProductEntity`, `ItemEntity`, los DAOs, `DefaultProductRepository`,
  `FirebaseFirestoreSyncRepository` y sus modelos de red. Es el cambio más grande de la capa de
  datos hasta ahora.
- Los docs que ya están en Firestore mantienen sus ids (los adopta el backfill), así que no hace
  falta limpiar el servidor. Sí conviene borrar los duplicados que dejaron los intentos anteriores.

## Alternativa descartada

**Tombstones** — registrar los borrados explícitamente en vez de inferirlos de la ausencia en el
snapshot. Resolvería lo mismo y además la familia de "dispositivo mucho tiempo offline", pero implica
una colección nueva, política de retención y su propia migración. Para una app de despensa personal
es desproporcionado frente a los ids estables.

## Referencias al código

- `app/src/main/java/dev/pcha/foodsense/app/data/ProductRepository.kt` — `applyRemoteChanges`,
  `migrateLocalDataToFirestore`, `syncIfAuthenticated`
- `app/src/main/java/dev/pcha/foodsense/app/data/local/database/Product.kt` — entidades y DAOs
- `app/src/main/java/dev/pcha/foodsense/app/data/sync/FirebaseFirestoreSyncRepository.kt` — el
  `collection.add(...)` a reemplazar y la asignación de UUIDs a ids en blanco
- `app/src/main/java/dev/pcha/foodsense/app/data/sync/di/SyncModule.kt` — `persistentCacheSettings`,
  el origen de la ventana offline
- `app/src/test/java/dev/pcha/foodsense/app/data/DefaultProductRepositoryTest.kt` —
  `applyRemoteChanges_offlineAddedProductAlreadyInFirestore_isNotDuplicated` es la regresión que
  documenta el síntoma
