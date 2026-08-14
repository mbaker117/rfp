# Catalog Upload UX — Design Spec
**Date:** 2026-08-14
**Status:** Approved

---

## 1. Problem

The home page Step 2 ("Select Suppliers") hides catalog upload behind a three-step sequence: type a supplier name in the combobox → select/create the chip → click the hidden "+ Upload Catalog" button on the chip. Users who want to upload a catalog directly never discover this option.

## 2. Goal

Make catalog upload a first-class, immediately visible action in Step 2 so a user can go: drop RFP → drop catalog → click Analyze — with no supplier knowledge required.

## 3. User Flow (new)

1. **Step 1** — Drop/select RFP document (unchanged)
2. **Step 2** — Primary action: drop/select a supplier catalog file
   - Auto-creates a supplier named after the file stem (e.g. `acme-catalog.pdf` → supplier name `"acme-catalog"`)
   - Auto-uploads the file to that supplier
   - Drop zone replaced by a confirmation chip: `📎 acme-catalog.pdf  ✕`
   - Removing the chip (✕) resets Step 2 to the empty drop zone
   - Secondary action: **"or choose an existing supplier →"** disclosure link that expands the existing `SupplierCombobox` below the drop zone
3. **Step 3** — Analyze button (unchanged); disabled until both RFP + at least one supplier are present

## 4. Component Changes

### `frontend/src/app/page.tsx`
- Replace Step 2 card body with:
  - **CatalogDropZone** (inline, not a separate file) — a drop zone accepting PDF/Word/Excel, same visual style as Step 1's `FileUpload`
  - When file selected: call `POST /suppliers` → `POST /suppliers/{id}/catalog/upload`; on success push the returned `supplierId` into `selectedSuppliers` state and store the filename for the chip
  - **Catalog chip** (shown when file is selected): `📎 {filename}  ✕` — clicking ✕ removes the supplier from selection and resets to drop zone
  - **Disclosure toggle** below: `"or choose an existing supplier →"` / `"← hide"` — toggles visibility of the existing `SupplierCombobox`
- The `selectedSuppliers` state already drives the Analyze step; no changes needed there

### `frontend/src/components/SupplierCombobox.tsx`
- No changes needed — rendered conditionally inside the disclosure section

## 5. Backend

No changes. Reuses existing endpoints:
- `POST /suppliers { name }` → `{ id, name, ... }`
- `POST /suppliers/{id}/catalog/upload` (multipart) → `{ supplierId, status }`

## 6. Error Handling

- If `POST /suppliers` fails: show inline error "Could not create supplier — please try again" in the Step 2 card
- If `POST /suppliers/{id}/catalog/upload` fails: same inline error; do not add the supplier to selection
- Loading state: show a spinner inside the drop zone during the two-call sequence

## 7. Out of Scope

- Catalog upload progress bar (file sizes expected to be small)
- Multiple direct catalog uploads (one drop zone → one auto-supplier; additional suppliers via combobox)
- Backend supplier deduplication by filename
