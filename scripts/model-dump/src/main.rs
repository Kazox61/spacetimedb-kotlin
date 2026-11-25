//! Dumps the parts of upstream's validated `ModuleDef` that the Kotlin generator reads, as JSON.
//! Oracle for the Kotlin normalizer: `model-dump <schema.json>` → `model.json`.
//! Needs the SpacetimeDB fork checked out next to this repository (`../SpacetimeDB`);
//! `scripts/capture-fixtures.sh` builds and runs it.
//! Also dumps convert_case results for every identifier seen, plus a list of tricky ones.

use std::collections::BTreeMap;

use convert_case::{Case, Casing};
use serde_json::{json, Value};
use spacetimedb_lib::db::raw_def::v9::TableAccess;
use spacetimedb_lib::{sats::serde::SerdeWrapper, RawModuleDef};
use spacetimedb_schema::def::{IndexAlgorithm, ModuleDef, TableDef};
use spacetimedb_schema::schema::{Schema, TableSchema};
use spacetimedb_schema::type_for_generate::{AlgebraicTypeDef, AlgebraicTypeUse, ProductTypeDef};

struct Ctx {
    idents: std::collections::BTreeSet<String>,
}

impl Ctx {
    fn id(&mut self, s: impl ToString) -> Value {
        let s = s.to_string();
        self.idents.insert(s.clone());
        Value::String(s)
    }
}

fn use_json(u: &AlgebraicTypeUse) -> Value {
    match u {
        AlgebraicTypeUse::Ref(r) => json!({ "Ref": r.0 }),
        AlgebraicTypeUse::Array(e) => json!({ "Array": use_json(e) }),
        AlgebraicTypeUse::Option(e) => json!({ "Option": use_json(e) }),
        AlgebraicTypeUse::Result { ok_ty, err_ty } => json!({ "Result": { "ok": use_json(ok_ty), "err": use_json(err_ty) } }),
        AlgebraicTypeUse::ScheduleAt => json!("ScheduleAt"),
        AlgebraicTypeUse::Identity => json!("Identity"),
        AlgebraicTypeUse::ConnectionId => json!("ConnectionId"),
        AlgebraicTypeUse::Timestamp => json!("Timestamp"),
        AlgebraicTypeUse::TimeDuration => json!("TimeDuration"),
        AlgebraicTypeUse::Uuid => json!("Uuid"),
        AlgebraicTypeUse::Unit => json!("Unit"),
        AlgebraicTypeUse::Never => json!("Never"),
        AlgebraicTypeUse::String => json!("String"),
        AlgebraicTypeUse::Primitive(p) => json!({ "Primitive": format!("{p:?}") }),
    }
}

fn product_json(cx: &mut Ctx, p: &ProductTypeDef) -> Value {
    Value::Array(
        p.elements
            .iter()
            .map(|(name, ty)| json!({ "name": cx.id(name), "type": use_json(ty) }))
            .collect(),
    )
}

fn table_json(cx: &mut Ctx, module: &ModuleDef, table: &TableDef, schema: &TableSchema) -> Value {
    let mut indexes: Vec<_> = table.indexes.values().collect();
    indexes.sort_by_key(|i| &i.name);
    let indexes: Vec<Value> = indexes
        .into_iter()
        .map(|idx| {
            let cols = idx.algorithm.columns();
            let (kind, columns): (&str, Vec<u16>) = match &idx.algorithm {
                IndexAlgorithm::BTree(_) => ("BTree", cols.iter().map(|c| c.0).collect()),
                IndexAlgorithm::Hash(_) => ("Hash", cols.iter().map(|c| c.0).collect()),
                IndexAlgorithm::Direct(_) => ("Direct", cols.iter().map(|c| c.0).collect()),
                _ => ("Other", cols.iter().map(|c| c.0).collect()),
            };
            json!({
                "name": idx.name.to_string(),
                "accessor_name": idx.accessor_name.as_ref().map(|a| cx.id(a)),
                "algorithm": kind,
                "columns": columns,
                "is_unique": schema.is_unique(&cols),
            })
        })
        .collect();
    let _ = module;
    json!({
        "name": cx.id(&table.name),
        "accessor_name": cx.id(&table.accessor_name),
        "product_type_ref": table.product_type_ref.0,
        "primary_key": table.primary_key.map(|c| c.0),
        "is_event": table.is_event,
        "public": table.table_access == TableAccess::Public,
        "columns": table.columns.iter().map(|c| json!({ "name": cx.id(&c.name), "accessor_name": cx.id(&c.accessor_name) })).collect::<Vec<_>>(),
        "indexes": indexes,
    })
}

fn module_json(cx: &mut Ctx, module: &ModuleDef) -> Value {
    let tables: Vec<Value> = module
        .tables()
        .map(|t| {
            let schema = TableSchema::from_module_def(module, t, (), 0.into()).validated().unwrap();
            table_json(cx, module, t, &schema)
        })
        .collect();
    let views: Vec<Value> = module
        .views()
        .map(|v| {
            let as_table = TableDef::from(v.clone());
            let schema = TableSchema::from_view_def_for_codegen(module, v).validated().unwrap();
            json!({
                "name": cx.id(&v.name),
                "accessor_name": cx.id(&v.accessor_name),
                "params": v.params.elements.iter().map(|e| e.name.as_ref().map(|n| n.to_string())).collect::<Vec<_>>(),
                "params_for_generate": product_json(cx, &v.params_for_generate),
                "as_table": table_json(cx, module, &as_table, &schema),
            })
        })
        .collect();
    let reducers: Vec<Value> = module
        .reducers()
        .map(|r| {
            json!({
                "name": cx.id(&r.name),
                "accessor_name": cx.id(&r.accessor_name),
                "params_for_generate": product_json(cx, &r.params_for_generate),
                "lifecycle": r.lifecycle.map(|l| format!("{l:?}")),
                "private": r.visibility.is_private(),
            })
        })
        .collect();
    let procedures: Vec<Value> = module
        .procedures()
        .map(|p| {
            json!({
                "name": cx.id(&p.name),
                "accessor_name": cx.id(&p.accessor_name),
                "params_for_generate": product_json(cx, &p.params_for_generate),
                "return_type_for_generate": use_json(&p.return_type_for_generate),
                "private": p.visibility.is_private(),
            })
        })
        .collect();
    let types: Vec<Value> = module
        .types()
        .map(|t| {
            json!({
                "name_segments": t.accessor_name.name_segments().map(|s| cx.id(s)).collect::<Vec<_>>(),
                "ref": t.ty.0,
            })
        })
        .collect();
    let mut defs: Vec<_> = module.typespace_for_generate().defs().iter().collect();
    defs.sort_by_key(|(r, _)| r.0);
    let defs: BTreeMap<String, Value> = defs
        .into_iter()
        .map(|(r, d)| {
            let v = match d {
                AlgebraicTypeDef::Product(p) => json!({ "Product": { "elements": product_json(cx, p), "recursive": p.recursive } }),
                AlgebraicTypeDef::Sum(s) => json!({ "Sum": {
                    "variants": s.variants.iter().map(|(n, ty)| json!({ "name": cx.id(n), "type": use_json(ty) })).collect::<Vec<_>>(),
                    "recursive": s.recursive,
                } }),
                AlgebraicTypeDef::PlainEnum(e) => json!({ "PlainEnum": { "variants": e.variants.iter().map(|n| cx.id(n)).collect::<Vec<_>>() } }),
            };
            (r.0.to_string(), v)
        })
        .collect();
    let submodules: Vec<Value> = module
        .submodules()
        .iter()
        .map(|(key, sub)| json!({ "key": cx.id(key), "module": module_json(cx, sub) }))
        .collect();
    json!({
        "path": module.path().to_string(),
        "path_segments": module.path().segments().iter().map(|s| cx.id(s)).collect::<Vec<_>>(),
        "accessor_path_segments": module.accessor_path().segments().iter().map(|s| cx.id(s)).collect::<Vec<_>>(),
        "tables": tables,
        "views": views,
        "reducers": reducers,
        "procedures": procedures,
        "types": types,
        "typespace_for_generate": defs,
        "submodules": submodules,
    })
}

const TRICKY: &[&str] = &[
    "HTTPServer", "httpServer", "user2Name", "user_2_name", "ABC123def", "a_b-c", "snake_case_name",
    "camelCaseName", "PascalCaseName", "SCREAMING_CASE", "mixed_UPPER_lower", "x", "X", "id", "ID",
    "userID", "UserId", "IOError", "v2", "V2", "v2_table", "table_v2", "Vec3", "vec3d", "u8_array",
    "__identity__", "_leading", "trailing_", "double__underscore", "with space", "kebab-case-name",
    "number1st", "1st", "aB", "Ab", "aBC", "ABc", "already_snake_2", "my_lib", "myLib", "MyLib",
];

fn main() {
    let path = std::env::args().nth(1).expect("usage: model-dump <schema.json>");
    let bytes = std::fs::read(&path).unwrap();
    let SerdeWrapper::<RawModuleDef>(raw) = serde_json::from_slice(&bytes).unwrap();
    let module = ModuleDef::try_from(raw).unwrap();

    let mut cx = Ctx { idents: Default::default() };
    let model = module_json(&mut cx, &module);

    let mut cases = BTreeMap::new();
    for s in cx.idents.iter().map(String::as_str).chain(TRICKY.iter().copied()) {
        cases.insert(
            s.to_string(),
            json!({ "snake": s.to_case(Case::Snake), "camel": s.to_case(Case::Camel), "pascal": s.to_case(Case::Pascal) }),
        );
    }
    let out = json!({ "model": model, "cases": cases });
    println!("{}", serde_json::to_string_pretty(&out).unwrap());
}
