
---

# Alma Remote Storage App — Complete Functional Documentation

## 1. Overview

This application is a **middleware/integration layer** that synchronizes bibliographic items, holdings, and patron requests between multiple **network institutions** (libraries using Ex Libris Alma) and a single **Shared Collection Facility (SCF)** — also called the "Remote Storage Institution." 

The app acts as an automated bridge: it listens for events (webhooks) from Alma, processes files exchanged via FTP, and calls Alma REST APIs to keep item records and requests in sync across institutions.

**Deployment model:** A Java web application (WAR) deployed on a servlet container (originally Heroku with webapp-runner). It also has a scheduled background process for log backup.

---

## 2. Architecture Summary

```
┌─────────────────────┐        ┌───────────────────────┐
│  Member Institution │        │  Remote Storage (SCF) │
│  (Alma instance)    │        │  (Alma instance)      │
└────────┬────────────┘        └────────┬──────────────┘
         │                              │
         │  Webhooks (HTTP POST)        │  Webhooks (HTTP POST)
         │  FTP files (items/requests)  │
         ▼                              ▼
┌─────────────────────────────────────────────────────────┐
│              Alma Remote Storage App                     │
│  ┌──────────┐  ┌──────────┐  ┌───────────┐            │
│  │ Webhook  │  │  Items   │  │ Requests  │            │
│  │ Servlet  │  │ Handler  │  │  Handler  │            │
│  └──────────┘  └──────────┘  └───────────┘            │
│       │              │              │                   │
│       ▼              ▼              ▼                   │
│  ┌────────────────────────────────────────┐            │
│  │        Alma REST API Client            │            │
│  │  (Bibs, Items, Holdings, Loans,        │            │
│  │   Requests, Users, Configuration)      │            │
│  └────────────────────────────────────────┘            │
│       │                                                │
│       ▼                                                │
│  ┌──────────┐                                          │
│  │FTP/SFTP  │                                          │
│  │ Client   │                                          │
│  └──────────┘                                          │
└─────────────────────────────────────────────────────────┘
         │
         ▼
┌─────────────────────┐
│    FTP/SFTP Server   │
│  (shared file area)  │
└──────────────────────┘
```

---

## 3. Configuration

### 3.1 Configuration Loading

The app loads its configuration from a JSON file (conf.json). The loading priority is:

1. **Environment variable `CONFIG_FILE`** — If set, the app fetches the config from this URL (supports `ftp://` URLs or any URL). This is the production approach.
2. **Classpath resource** — If the env var is not set, it looks for conf.json in the classpath (e.g., resources).

Configuration is a **singleton** that can be reloaded at runtime by calling `GET /configuration`.

### 3.2 Configuration Structure (JSON)

```json
{
  "gateway": "<Alma API base URL, e.g. https://api-na.hosted.exlibrisgroup.com>",
  "main_local_folder": "<local temp directory for file processing, e.g. /tmp>",
  "report_file_folder": "<path where CSV error reports are written>",
  "max_number_of_threads": "<number of parallel threads for item processing, default 4>",
  "ftp_server": {
    "host": "<FTP/SFTP hostname>",
    "user": "<FTP username>",
    "password": "<FTP password>",
    "main_folder": "<root directory on FTP>",
    "ftp_port": "<port: 21 for FTP, 22 for SFTP>"
  },
  "remote_storage_inst": "<institution code of the SCF, e.g. 01AAA_RS>",
  "remote_storage_apikey": "<API key for the SCF institution>",
  "remote_storage_holding_library": "<library code in SCF for holdings, e.g. RS>",
  "remote_storage_holding_location": "<default location code in SCF, e.g. rs_shared>",
  "remote_storage_circ_desc": "<circulation desk code for SCF loans>",
  "remote_storage_digitization_department": "<digitization department code in SCF>",
  "webhook_secret": "<HMAC secret for validating webhook signatures>",
  "ignore_delete_files": "<true|false - whether to skip processing delete files>",
  "institutions": [
    {
      "code": "<institution code, e.g. 01AAA_AB>",
      "apikey": "<API key for this institution>",
      "requests_job_id": "<Alma job ID for remote storage request export>",
      "publishing_job_id": "<Alma job ID for item publishing>",
      "default_library": "<fallback library code for this institution>",
      "libraries": [
        {
          "code": "<library code, e.g. ABRS>",
          "circ_desc": "<circulation desk for scan-in operations>",
          "remote_storage_location": ["<location1>", "<location2>"]
        }
      ]
    }
  ]
}
```

**Key concepts:**
- `remote_storage_location` arrays define which locations in a member institution map to items stored in remote storage.
- The `ftp_port` determines protocol: `"22"` → SFTP, anything else → FTP.
- Each institution has its own API key, job IDs, and library configuration.

---

## 4. HTTP Endpoints (Servlets)

### 4.1 `GET /webhook` — Webhook Challenge

**Purpose:** Alma sends a challenge when activating a webhook profile. The app must echo it back.

**Request:** `GET /webhook?challenge=<value>`

**Response:** JSON `{"challenge": "<value>"}`

### 4.2 `POST /webhook` — Webhook Event Receiver

**Purpose:** Receives all webhook notifications from Alma.

**Flow:**
1. Read the full request body (JSON string).
2. If `webhook_secret` is configured, validate the `X-Exl-Signature` header:
   - Compute HMAC-SHA256 of the body using the secret as key.
   - Base64-encode the result.
   - Compare with the signature header. If mismatch, reject.
3. Immediately respond `"Got message"` to the caller.
4. Spawn a **new thread** to process the message asynchronously.

**Signature validation algorithm:**
```
signature = Base64( HMAC-SHA256( request_body_bytes, secret_bytes ) )
```
Compare computed value with `X-Exl-Signature` header.

### 4.3 `GET /configuration` — Reload Configuration

**Purpose:** Triggers a fresh reload of conf.json from its source.

**Response:** `"End Update Configuration"`

### 4.4 `GET /logger?level=<level>` — Change Log Level

**Purpose:** Dynamically change the application log level at runtime.

**Parameters:** `level` — one of `DEBUG`, `INFO`, `WARN`, `ERROR`, etc.

---

## 5. Webhook Message Processing

### 5.1 Message Structure

All webhook messages from Alma are JSON with these key fields:
- `action` — one of: `JOB_END`, `LOAN`, `REQUEST`
- `institution.value` — the institution code that triggered the event
- `event.value` — specific event type (for LOAN and REQUEST actions)

### 5.2 Action: `JOB_END`

When an Alma job finishes, the app checks which institution and job type it belongs to:

1. Extract `job_instance.job_info.id` from the message.
2. Search all configured institutions for a matching `publishing_job_id`:
   - If found → call **Items Synchronization** (`mergeItemsWithSCF`) for that institution.
3. If not found as publishing job, search for matching `requests_job_id`:
   - If found → call **Request Processing** (`sendRequestsToSCF`) for that institution.
4. If no match → ignore (log debug message).

### 5.3 Action: `LOAN` (Event: `LOAN_RETURNED`)

Only processed when the webhook originates from the **Remote Storage institution**.

**Purpose:** When an item is returned at the SCF, notify the originating member institution so they can "scan in" the item on their end (triggering fulfillment of the original request).

**Flow:**
1. Extract `user_id` and `item_barcode` from `item_loan`.
2. Check if barcode ends with `"X"` (SCF items have barcodes = original barcode + "X"). If not, ignore.
3. Strip the trailing "X" to get the original barcode.
4. Look up the SCF item by barcode + "X" to get its `provenance` (the originating institution code).
5. Look up the same item in the originating institution (by original barcode).
6. **Scan in** the item at the originating institution (triggers fulfillment workflow).

**Scan-in details:**
- Determine the correct library from the item's current location (or temp location).
- Look up the circulation desk from configuration.
- Call `POST /almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{itemPid}?op=scan&library={lib}&circ_desk={desk}&done=true`

### 5.4 Action: `REQUEST` (Event: `REQUEST_CANCELED`)

Only processed when the webhook originates from the **Remote Storage institution**.

**Purpose:** When a request is canceled in the SCF, propagate the cancellation back to the originating institution.

**Two sub-scenarios:**

#### 5.4.1 Barcode-based cancellation
- The webhook has `user_request.barcode` ending in "X".
- Strip "X", look up SCF item to find provenance (originating institution).
- Look up the item in the originating institution.
- Get all requests on that item in the originating institution.
- Cancel each request with reason "Remote storage cannot fulfill the request".

#### 5.4.2 Comment-based (Bib-level) cancellation
- The webhook has `user_request.comment` containing a pattern: `{Source Request INSTITUTION-requestId-userPrimaryId}`
- Parse out the institution code, request ID, and user ID.
- Cancel the request in the originating institution using either title-level or user-level request cancellation API.

---

## 6. Items Synchronization (`mergeItemsWithSCF`)

**Trigger:** Webhook `JOB_END` with a matching `publishing_job_id`.

**Purpose:** Synchronize physical item records from a member institution to the SCF. Items published from the institution via Alma's publishing profile are compared against the SCF and created/updated/deleted as needed.

### 6.1 Overall Flow

1. Determine local working directory: `{main_local_folder}/files/items/`
2. Clean the local `targz/` subdirectory.
3. Connect to FTP/SFTP and download all files from `{ftp_main_folder}/{institution_code}/items/` into local `targz/`.
4. Extract all `.tar.gz` files into local `xml/` folder.
5. Process each XML file using a **thread pool** (configurable size, default 4).
6. If `ignore_delete_files` is `true`, skip files containing `_delete` in the name.

### 6.2 File Processing per XML File

Each XML file contains MARC21 records. For each record:

1. Parse as MARC4J `Record` objects.
2. Extract the **Network Zone MMS ID** from field `035$a` (value containing "EXLNZ").
3. Extract item data from repeatable field `ITM`:
   - `$b` = barcode
   - `$c` = library code
   - `$l` = location code
   - `$n` = internal note 2
4. If the filename contains `_delete`, call `itemDeleted()`. Otherwise call `itemUpdated()`.

### 6.3 `itemUpdated(itemData)` Logic

```
IF item exists in SCF (lookup by barcode + "X"):
    IF item is NOT in a remote-storage location (in the institution):
        IF ignore_delete_files is true:
            Do nothing (log: ignoring delete)
        ELSE:
            Delete the item from SCF
    ELSE:
        Update the SCF item with fresh data from the institution item
ELSE (item does NOT exist in SCF):
    IF item is NOT in a remote-storage location (in the institution):
        Do nothing (not relevant for remote storage)
    ELSE:
        Need to create the item in SCF:
        1. Find or create a BIB record in SCF
        2. Find or create a HOLDING record in SCF
        3. Create the ITEM in SCF
        4. Create a LOAN on the new item (to the institution's system user)
```

#### 6.3.1 Finding/Creating BIB in SCF

**If Network Zone MMS ID exists:**
- Search SCF bibs by NZ MMS ID: `GET /almaws/v1/bibs?nz_mms_id={id}`
- If not found → create bib from NZ: `POST /almaws/v1/bibs?from_nz_mms_id={id}`

**If NO Network Zone MMS ID:**
- Search SCF bibs by "other system ID" using pattern `(INSTITUTION_CODE)LOCAL_MMS_ID`: `GET /almaws/v1/bibs?other_system_id={value}`
- If not found → create bib with the MARC record XML (adds 035 field with institution prefix, removes AVA fields): `POST /almaws/v1/bibs`

#### 6.3.2 Finding/Creating HOLDING in SCF

- Get all holdings for the BIB: `GET /almaws/v1/bibs/{mmsId}/holdings`
- Look for a holding matching the SCF library and location.
- If not found → create holding with template:
  ```xml
  <holding>
    <record>
      <datafield tag="852" ind1="0" ind2=" ">
        <subfield code="b">{library_code}</subfield>
        <subfield code="c">{location_code}</subfield>
      </datafield>
    </record>
    <suppress_from_publishing>false</suppress_from_publishing>
  </holding>
  ```
- The location is determined by checking if the item's location exists in the SCF library's locations (via API call to `/almaws/v1/conf/libraries/{lib}/locations`). If not, use the default location from config.

#### 6.3.3 Creating ITEM in SCF

1. Retrieve the full item record from the institution: `GET /almaws/v1/items?item_barcode={barcode}`
2. Modify the item data:
   - Set barcode to `{original_barcode}X`
   - Set `provenance` to the institution code
   - Remove: `po_line`, `library`, `location`, `policy`, `internal_note_1`, `internal_note_3`, `call_number`, `temp_library`, `in_temp_location`, `temp_location`, `temp_policy`
3. Create item: `POST /almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items`

#### 6.3.4 Creating LOAN in SCF

After creating the item, wait 3 seconds, then create a loan:
- User ID = `{institution_code}-{library_code}` (the system patron)
- Loan body includes `circ_desk` and `library` from config
- `POST /almaws/v1/users/{userId}/loans?item_pid={pid}`

#### 6.3.5 Updating SCF Item

When an item already exists in both places:
1. Get the institution's current item data.
2. Merge: keep the SCF's `pid`, `barcode` (with X), `provenance`, `library`, `location`, `alternative_call_number`, `alternative_call_number_type`, `storage_location_id`, `internal_note_1`, `internal_note_3`, `statistics_note_2`, `holding_data`, `bib_data`.
3. Take all other metadata from the institution item.
4. `PUT /almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}`

### 6.4 `itemDeleted(itemData)` Logic

1. Look up item in SCF by barcode + "X".
2. If found → delete it: `DELETE /almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}`

### 6.5 Determining if Item is in Remote Storage

Check if the item's library + location matches any entry in the configured `institutions[].libraries[].remote_storage_location` array for the item's institution.

---

## 7. Request Processing (`sendRequestsToSCF`)

**Trigger:** Webhook `JOB_END` with a matching `requests_job_id`.

**Purpose:** Process remote storage request files exported by Alma and create corresponding requests in the SCF.

### 7.1 Overall Flow

1. Clean local working directory: `{main_local_folder}/files/requests/xml/`
2. Download XML files from FTP: `{ftp_main_folder}/{institution_code}/requests/`
3. Parse each XML file into a list of `ItemData` request objects.
4. Route each request to the appropriate handler based on type and barcode format.
5. If any handler returns `false` (failure), cancel the original request in the source institution.

### 7.2 XML Request Parsing

Each request XML contains `<xb:...>` elements:
- `xb:barcode` — item barcode (may be null for bib-level requests)
- `xb:mmsId` — bibliographic MMS ID
- `xb:description` — item description
- `xb:requestType` — one of: `HOLD`, `PHYSICAL_TO_DIGITIZATION`, `STAFF_PHYSICAL_DIGITIZATION`, `RESOURCE_SHARING_P2D_SHIPMENT`
- `xb:requestId` — the original request ID
- `xb:institutionCode` — pick-up institution (may differ from source)
- `xb:libraryCode` or `xb:pickup > xb:library` — pick-up library
- `xb:patronInfo` — contains `xb:patronName`, `xb:patronIdentifier`, `xb:patronEmail`, `xb:patronAddress`
- `xb:requestNote` — free-text note

**Library resolution:** If a library code is not directly available, the app resolves library name → code via the Alma Configuration API (`GET /almaws/v1/conf/libraries`). Falls back to the institution's `default_library` from config.

### 7.3 Request Routing Logic

```
IF barcode contains space or colon (= title/bib-level):
    IF type == PHYSICAL_TO_DIGITIZATION AND userId exists:
        → createDigitizationUserRequest (patron digitization at bib level)
    ELSE IF type == STAFF_PHYSICAL_DIGITIZATION or RESOURCE_SHARING_P2D_SHIPMENT:
        → createSystemUserDigitizationTitleRequest
    ELSE:
        → createBibRequest

ELSE IF barcode is valid (non-empty, no spaces/colons):
    IF type == PHYSICAL_TO_DIGITIZATION:
        → createDigitizationItemRequest (patron digitization at item level)
    ELSE IF type == STAFF_PHYSICAL_DIGITIZATION or RESOURCE_SHARING_P2D_SHIPMENT:
        → createSystemUserDigitizationItemRequest
    ELSE:
        → createItemRequest

ELSE (no barcode):
    → createBibRequest
```

### 7.4 `createItemRequest(requestData)` — Physical Item Request

1. Look up SCF item by barcode + "X".
2. If not found → fail (cancel source request).
3. Check process_type. If `"LOAN"`:
   - Item is currently on loan → cancel the source institution's request with note "Item is on loan".
   - Return success (don't create SCF request).
4. Otherwise, create request in SCF:
   - Build request object: type=`HOLD`, sub_type=`PATRON_PHYSICAL`, pickup=`USER_HOME_ADDRESS`, task=`Pickup From Shelf`
   - User ID = `{institution_code}-{library_code}`
   - Comment includes: request note, patron info, request type, internal identifier
   - `POST /almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}/requests?user_id={userId}&allow_same_request=true`

### 7.5 `createBibRequest(requestData)` — Title-level Request

1. Get the original request details from the source institution.
2. Get the source institution's BIB record.
3. Extract Network Zone number from `network_number` array.
4. Find corresponding BIB in SCF (by NZ number or by other_system_id).
5. Create bib-level request in SCF:
   - User ID = `{institution_code}-{library_code}`
   - Comment includes source institution, patron info, source request type, internal identifier, and `{Source Request INSTITUTION-requestId-userPrimaryId}` tag.
   - Copies over: `description`, `manual_description`, `volume`, `issue`, `part`, `date_of_publication`.
   - If `manual_description` exists, try to attach to specific holding in SCF.
   - `POST /almaws/v1/bibs/{mmsId}/requests?user_id={userId}&allow_same_request=true`

### 7.6 `createDigitizationItemRequest(requestData)` — Patron Digitization (Item Level)

1. Get the original request from source institution.
2. Get the patron info from the source institution (`GET /almaws/v1/users/{userId}?user_id_type=all_unique`).
3. Find or create the patron as a **linked user** in the SCF:
   - Check `source_link_id` / `linking_id` on the institution user.
   - Try to find existing linked user in SCF: `GET /almaws/v1/users/{linkId}?source_institution_code={inst}`
   - If not found, create: `POST /almaws/v1/users?source_institution_code={inst}&source_user_id={id}`
4. Refresh the linked user: `POST /almaws/v1/users/{userId}?op=refresh`
5. Get SCF item by barcode + "X".
6. Create digitization request in SCF:
   - Type = `DIGITIZATION`, sub_type = `PHYSICAL_TO_DIGITIZATION`
   - Target destination = configured `remote_storage_digitization_department`
   - Copies: `copyrights_declaration_signed_by_patron`, `description`, `partial_digitization`, `required_pages_range`, `full_chapter`, `chapter_or_article_title`, `chapter_or_article_author`, `manual_description`, `last_interest_date`, `volume`, `issue`, `part`, `date_of_publication`
   - `POST /almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}/requests?user_id={userId}`
7. If successful, cancel the title request in the source institution (with reason "RequestSwitched").

### 7.7 `createDigitizationUserRequest(requestData)` — Patron Digitization (Bib Level)

Same as item-level digitization, but:
- Works with a BIB in SCF (found by NZ or other_system_id).
- Creates a bib-level digitization request.
- `POST /almaws/v1/bibs/{mmsId}/requests?user_id={userId}`

### 7.8 `createSystemUserDigitizationItemRequest` — Staff Digitization (Item Level)

For `STAFF_PHYSICAL_DIGITIZATION` or `RESOURCE_SHARING_P2D_SHIPMENT`:
- No real patron — uses system user ID = `{institution_code}-{library_code}`.
- Gets the original request, finds the SCF item.
- Creates digitization request using the system user.

### 7.9 `createSystemUserDigitizationTitleRequest` — Staff Digitization (Bib Level)

- Uses system user ID.
- Gets original request, finds SCF bib.
- Creates bib-level digitization request.

### 7.10 Request Cancellation on Failure

If any request handler returns `false`, the app cancels the original request in the source institution:
- If `mmsId` is available: `DELETE /almaws/v1/bibs/{mmsId}/requests/{requestId}?reason=CannotBeFulfilled&notify_user=true&note=Request could not be fulfilled by the SCF.`
- If only `userId` is available: `DELETE /almaws/v1/users/{userId}/requests/{requestId}?reason=CannotBeFulfilled&notify_user=true`

---

## 8. FTP/SFTP Client

### 8.1 Protocol Selection

- Port `22` → SFTP (using JSch library)
- Any other port → FTP (using Apache Commons Net)

### 8.2 File Download Behavior

1. Connect to server.
2. List files in the specified remote directory.
3. Create an `OLD` subdirectory (if files exist).
4. For each file:
   - **Rename** the file to `OLD/{filename}` (atomic move to prevent concurrent processing).
   - Download from `OLD/{filename}` to local directory.
   - If download fails, move file back to original location.
5. Disconnect.

### 8.3 File Upload (for logs)

- Upload a single local file to a specified remote path.
- Used for log backup only.

---

## 9. Alma REST API Calls

All API calls go through a central HTTP client (`AlmaRestUtil`) with retry logic.

### 9.1 Retry Logic

- If response code is 500–505 (server error), wait 5 minutes and retry.
- Maximum retries: 36 (approximately 3 hours total).
- If response code is 429 (rate limited), do NOT retry.
- API key is appended as query parameter `apikey=`.
- Content-Type is auto-detected: if body is valid JSON → `application/json`, otherwise → `application/xml`.
- Accept header is always `application/json`.
- Connection timeout: 20 seconds. Read timeout: 500 seconds.

### 9.2 API Endpoints Used

| API | Method | Endpoint | Purpose |
|-----|--------|----------|---------|
| **Bibs** | GET | `/almaws/v1/bibs?nz_mms_id={id}&view=full&expand=p_avail` | Find bib by Network Zone ID |
| **Bibs** | GET | `/almaws/v1/bibs?other_system_id={id}&view=full&expand=p_avail` | Find bib by institution system ID |
| **Bibs** | GET | `/almaws/v1/bibs/{mmsId}?view=full&expand=None` | Get single bib |
| **Bibs** | POST | `/almaws/v1/bibs?from_nz_mms_id={id}&validate=false&override_warning=true` | Create bib from NZ |
| **Bibs** | POST | `/almaws/v1/bibs?validate=false&override_warning=true` | Create local bib |
| **Holdings** | GET | `/almaws/v1/bibs/{mmsId}/holdings` | List holdings |
| **Holdings** | POST | `/almaws/v1/bibs/{mmsId}/holdings` | Create holding |
| **Items** | GET | `/almaws/v1/items?item_barcode={barcode}` | Find item by barcode |
| **Items** | POST | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items` | Create item |
| **Items** | PUT | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}` | Update item |
| **Items** | DELETE | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}` | Delete item |
| **Items** | POST | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}?op=scan&library={lib}&circ_desk={desk}&done=true` | Scan-in item |
| **Requests** | POST | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}/requests?user_id={id}&allow_same_request=true` | Create item request |
| **Requests** | POST | `/almaws/v1/bibs/{mmsId}/requests?user_id={id}&allow_same_request=true` | Create bib request |
| **Requests** | GET | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}/requests` | List item requests |
| **Requests** | GET | `/almaws/v1/bibs/{mmsId}/requests/{requestId}` | Get bib request |
| **Requests** | DELETE | `/almaws/v1/bibs/{mmsId}/holdings/{holdingId}/items/{pid}/requests/{id}?reason={r}&note={n}` | Cancel item request |
| **Requests** | DELETE | `/almaws/v1/bibs/{mmsId}/requests/{requestId}?reason={r}&notify_user={bool}&note={n}` | Cancel title request |
| **Requests** | DELETE | `/almaws/v1/users/{userId}/requests/{requestId}?reason={r}&notify_user={bool}&note={n}` | Cancel user request |
| **Loans** | POST | `/almaws/v1/users/{userId}/loans?item_pid={pid}` | Create loan |
| **Users** | GET | `/almaws/v1/users/{userId}?user_id_type={type}` | Get user |
| **Users** | GET | `/almaws/v1/users/{userId}?source_institution_code={inst}` | Get linked user |
| **Users** | GET | `/almaws/v1/users/{userId}/requests/{requestId}` | Get user request |
| **Users** | POST | `/almaws/v1/users?source_institution_code={inst}&source_user_id={id}` | Create linked user |
| **Users** | POST | `/almaws/v1/users/{userId}?op=refresh` | Refresh linked user |
| **Config** | GET | `/almaws/v1/conf/libraries` | List libraries |
| **Config** | GET | `/almaws/v1/conf/libraries/{code}/locations` | List library locations |

---

## 10. Data Model: `ItemData`

Central data transfer object carrying information for both items and requests:

| Field | Type | Description |
|-------|------|-------------|
| `barcode` | String | Item barcode (original, without "X") |
| `institution` | String | Institution code (pick-up institution for requests) |
| `library` | String | Library code |
| `location` | String | Location code |
| `networkNumber` | String | Network Zone MMS ID |
| `mmsId` | String | Local MMS ID in the institution |
| `description` | String | Item description (for requests) |
| `note` | String | Internal note 2 from MARC ITM field |
| `record` | MARC Record | Full MARC record (for bib creation when no NZ number) |
| `requestType` | String | Request type code |
| `requestId` | String | Original request ID |
| `userId` | String | Patron identifier |
| `sourceInstitution` | String | The institution that originated the request |
| `patron` | PatronInfo | Patron details (name, id, email, address) |
| `requestNote` | String | Free-text note from the request |

---

## 11. Barcode Convention

**Critical rule:** Items in the SCF always have barcode = `{original_barcode}X`

- When looking up an item in the SCF, append "X" to the barcode.
- When a webhook returns an SCF barcode ending in "X", strip it to get the original.
- If a barcode does NOT end in "X" in the SCF context, it's not part of this system — ignore it.

---

## 12. User/Patron Convention for the SCF

- **System users** (for physical requests): ID format is `{institution_code}-{library_code}` (e.g., `01AAA_AB-ABRS`). These must be pre-created in the SCF with a home address.
- **Linked patron users** (for digitization): Created dynamically in the SCF by linking to the source institution user via `source_institution_code` and `source_user_id`.

---

## 13. Error Reporting

The `ReportUtil` class writes CSV error reports:
- File pattern: `{report_file_folder}/{ReportName}_log_{yyyyMMdd}.csv`
- Columns: `Barcode, Institution, Message`
- Reports are appended throughout the day.

---

## 14. Log Backup Scheduler

A separate process (`SchedulerMain`) runs as a Heroku worker:
- Uses Quartz scheduler.
- Daily at 00:10 UTC: uploads the previous day's log file to FTP.
- File source: `logs/application.log_{yyyy-MM-dd}.log`
- If not found, falls back to current `logs/application.log`.

---

## 15. Library Code Resolution

When processing requests, if only a library *name* is available (not code):
1. Call `GET /almaws/v1/conf/libraries` for the institution.
2. Build a name→code map (cached in memory per institution).
3. Look up the code by name.
4. If all resolution fails, use the institution's `default_library` from config.

---

## 16. Concurrency Model

- Webhook messages are processed in **separate threads** (one thread per message).
- Item file processing uses a **fixed thread pool** (configurable, default 4 threads).
- `mergeItemsWithSCF` and `sendRequestsToSCF` are **synchronized** methods — only one instance runs at a time.
- FTP `getFiles` is **synchronized** — prevents concurrent file downloads.

---

## 17. Key Business Rules Summary

1. **Items in remote storage** in a member institution must have a mirror copy in the SCF with barcode suffix "X".
2. When an item is created/moves into a remote-storage location → create it in the SCF and loan it to the institution's system user.
3. When an item is updated → sync metadata to the SCF copy.
4. When an item leaves a remote-storage location or is deleted → optionally delete from SCF (controlled by `ignore_delete_files`).
5. When a patron requests an item → create a hold request in the SCF against the system user.
6. When the SCF returns a loaned item → scan it in at the member institution to trigger fulfillment.
7. When a request is canceled in the SCF → propagate cancellation to the originating institution.
8. If the SCF item is already on loan when a new request arrives → cancel the source request immediately.
9. Digitization requests create linked users in the SCF and create digitization-type requests; then cancel the original title-level request in the source institution.

---

## 18. External Dependencies

| Dependency | Purpose |
|------------|---------|
| Apache Commons Net | FTP client |
| JSch | SFTP client |
| MARC4J | MARC21 record parsing/writing |
| org.json | JSON parsing |
| jarchivelib | tar.gz extraction |
| Apache Commons IO | File utilities |
| Quartz | Job scheduling (log backup) |
| Log4j 1.2 | Logging |
| javax.servlet | HTTP servlet container |
| JAXB | XML binding (minimal usage) |

---

## 19. Deployment Notes

- Packaged as a WAR file, run with `webapp-runner.jar` (embedded Tomcat).
- Port from environment variable `$PORT`.
- Configuration via environment variable `CONFIG_FILE` pointing to FTP URL of conf.json.
- Two processes: `web` (the servlet app) and `scheduler` (log backup cron job).