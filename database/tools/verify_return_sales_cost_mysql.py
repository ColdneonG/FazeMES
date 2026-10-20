"""Run additive return-cost migration checks in a freshly owned, isolated MySQL schema.

Only loopback port 59685 is accepted. No application schema can be selected.
The disposable schema is removed on completion; result JSON is preserved.
"""
import argparse
import json
import pathlib
import subprocess
import uuid


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--mysql", required=True)
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--result", required=True)
    args = parser.parse_args()
    if args.port != 59685:
        raise ValueError("Only the explicitly isolated loopback test instance on 59685 is allowed")
    schema = "return_cost_it_" + uuid.uuid4().hex
    command = [args.mysql, "--no-defaults", "--host=127.0.0.1", f"--port={args.port}",
               "--user=root", "--default-character-set=utf8mb4", "--batch", "--raw", "--skip-column-names"]
    checks = []

    def sql(source, database=True, expected=True):
        call = subprocess.run(command + ([schema] if database else []), input=source,
                              text=True, encoding="utf-8", capture_output=True)
        if expected and call.returncode:
            raise RuntimeError(call.stderr.strip())
        return call

    def check(name, condition):
        if not condition:
            raise AssertionError(name)
        checks.append(name)

    def value(source):
        return sql(source).stdout.strip()

    root = pathlib.Path(__file__).resolve().parents[2]
    baseline = (root / "database/migrations/20261018_broky_cost.sql").read_text(encoding="utf-8")
    baseline = baseline.split("-- Cost permissions", 1)[0]
    migration = (root / "database/migrations/20261020_return_sales_cost.sql").read_text(encoding="utf-8")
    result = {"schema": schema, "port": args.port, "checks": checks, "passed": False}
    sql(f"CREATE DATABASE `{schema}` CHARACTER SET utf8mb4", database=False)
    try:
        check("owned schema starts empty", value("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()") == "0")
        sql(baseline)
        sql("""
          INSERT INTO mes_cost_stock_layer(id,tenant_id,material_stock_id,origin_workorder_id,quantity,remaining_quantity,unit_cost,status)
            VALUES(1,1,50,99,3,0,0.33333333,'FINAL');
          INSERT INTO mes_cost_allocation(id,tenant_id,transaction_id,layer_id,quantity,unit_cost,material_amount,labor_amount,overhead_amount,status)
            VALUES(1,1,100,1,3,0.33333333,0.7,0.2,0.1,'FINAL');
        """)
        original = value("SELECT * FROM mes_cost_allocation ORDER BY id")
        old_layer_query = "SELECT id,tenant_id,material_stock_id,inbound_transaction_id,origin_workorder_id,quantity,remaining_quantity,unit_cost,status,create_time,deleted FROM mes_cost_stock_layer ORDER BY id"
        original_layer = value(old_layer_query)
        sql(migration)
        check("all three additive tables created", value("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND table_name IN('mes_cost_return_source_balance','mes_cost_return_allocation','mes_cost_layer_basis')") == "3")
        check("three FIFO quantities match warehouse precision", value("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='mes_cost_stock_layer' AND column_name IN('quantity','remaining_quantity')) OR (table_name='mes_cost_allocation' AND column_name='quantity')) AND numeric_precision=20 AND numeric_scale=6") == "3")
        check("old immutable allocations unchanged", value("SELECT * FROM mes_cost_allocation ORDER BY id") == original)
        check("old FIFO values unchanged", value(old_layer_query) == original_layer)
        check("ordinary historical layers require no return basis", value("SELECT return_basis_required+0 FROM mes_cost_stock_layer WHERE id=1") == "0")
        check("no invented historical return balances", value("SELECT (SELECT COUNT(*) FROM mes_cost_return_source_balance)+(SELECT COUNT(*) FROM mes_cost_return_allocation)+(SELECT COUNT(*) FROM mes_cost_layer_basis)") == "0")
        check("no foreign-key locks on old sources", value("SELECT COUNT(*) FROM information_schema.referential_constraints WHERE constraint_schema=DATABASE() AND table_name IN('mes_cost_return_source_balance','mes_cost_return_allocation','mes_cost_layer_basis')") == "0")
        sql("""
          INSERT INTO mes_cost_return_source_balance(tenant_id,source_transaction_id,source_part_key,original_allocation_id,source_quantity,source_status,source_unit_cost,source_material_amount,source_labor_amount,source_overhead_amount,returned_quantity,returned_material_amount,returned_labor_amount,returned_overhead_amount)
            VALUES(1,100,1,1,3,'FINAL',0.33333333,0.7,0.2,0.1,1,0.23333333,0.06666667,0.03333333);
          INSERT INTO mes_cost_stock_layer(id,tenant_id,material_stock_id,inbound_transaction_id,quantity,remaining_quantity,unit_cost,status)
            VALUES(2,1,60,200,1,1,0.33333333,'FINAL');
          INSERT INTO mes_cost_layer_basis(tenant_id,layer_id,basis_quantity,material_amount,labor_amount,overhead_amount,consumed_material_amount,consumed_labor_amount,consumed_overhead_amount,status,origin_return_transaction_id)
            VALUES(1,2,1,0.23333333,0.06666667,0.03333333,0,0,0,'FINAL',200);
          INSERT INTO mes_cost_return_allocation(tenant_id,source_transaction_id,source_part_key,original_allocation_id,return_transaction_id,restored_layer_id,quantity,original_status,original_unit_cost,original_quantity,original_material_amount,original_labor_amount,original_overhead_amount,material_amount,labor_amount,overhead_amount,status)
            VALUES(1,100,1,1,200,2,1,'FINAL',0.33333333,3,0.7,0.2,0.1,0.23333333,0.06666667,0.03333333,'FINAL');
        """)
        before = [value(f"SELECT * FROM {table} ORDER BY 1,2") for table in
                  ("mes_cost_return_source_balance", "mes_cost_return_allocation", "mes_cost_layer_basis")]
        sql(migration)
        after = [value(f"SELECT * FROM {table} ORDER BY 1,2") for table in
                 ("mes_cost_return_source_balance", "mes_cost_return_allocation", "mes_cost_layer_basis")]
        check("retry preserves counters audits and basis exactly", before == after)
        check("retry preserves original immutable allocation", value("SELECT * FROM mes_cost_allocation ORDER BY id") == original)
        duplicate = sql("INSERT INTO mes_cost_return_source_balance(tenant_id,source_transaction_id,source_part_key,source_quantity,source_status) VALUES(1,100,1,3,'UNKNOWN')", expected=False)
        check("source budget part is unique", duplicate.returncode != 0 and "Duplicate entry" in duplicate.stderr)
        duplicate = sql("INSERT INTO mes_cost_return_allocation(tenant_id,source_transaction_id,source_part_key,return_transaction_id,restored_layer_id,quantity,original_status,original_quantity,status) VALUES(1,100,1,200,999,1,'FINAL',3,'FINAL')", expected=False)
        check("return posting part is unique", duplicate.returncode != 0 and "Duplicate entry" in duplicate.stderr)
        sql("INSERT INTO mes_cost_return_source_balance(tenant_id,source_transaction_id,source_part_key,source_quantity,source_status) VALUES(2,100,1,3,'UNKNOWN')")
        check("tenant-specific source budgets independent", value("SELECT COUNT(*) FROM mes_cost_return_source_balance WHERE source_transaction_id=100 AND source_part_key=1") == "2")
        sql("""
          INSERT INTO mes_cost_stock_layer(id,tenant_id,material_stock_id,quantity,remaining_quantity,status)
            VALUES(3,1,61,99999999999999.123456,99999999999999.123456,'INCOMPLETE');
          INSERT INTO mes_cost_layer_basis(tenant_id,layer_id,basis_quantity,status,origin_return_transaction_id)
            VALUES(1,3,99999999999999.123456,'INCOMPLETE',201);
          INSERT INTO mes_cost_return_source_balance(tenant_id,source_transaction_id,source_part_key,source_quantity,source_status)
            VALUES(1,101,0,99999999999999.123456,'UNKNOWN');
        """)
        check("full warehouse decimal quantity retained", value("SELECT CONCAT(quantity,'|',remaining_quantity) FROM mes_cost_stock_layer WHERE id=3") == "99999999999999.123456|99999999999999.123456")
        check("unknown return components remain null", value("SELECT material_amount IS NULL AND labor_amount IS NULL AND overhead_amount IS NULL AND consumed_material_amount IS NULL FROM mes_cost_layer_basis WHERE tenant_id=1 AND layer_id=3") == "1")
        sql("START TRANSACTION; UPDATE mes_cost_return_source_balance SET returned_quantity=2 WHERE tenant_id=1 AND source_transaction_id=100 AND source_part_key=1; ROLLBACK;")
        check("cost counter rollback uses InnoDB", value("SELECT returned_quantity FROM mes_cost_return_source_balance WHERE tenant_id=1 AND source_transaction_id=100 AND source_part_key=1") == "1.000000")
        result["passed"] = True
    except Exception as error:
        result["error"] = str(error)
        raise
    finally:
        # The name is generated above; never accept arbitrary schemas for cleanup.
        if not schema.startswith("return_cost_it_") or not schema[15:].isalnum():
            raise AssertionError("Refusing unsafe cleanup")
        sql(f"DROP DATABASE `{schema}`", database=False)
        pathlib.Path(args.result).write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps({"passed": result["passed"], "checks": len(checks), "result": args.result}, ensure_ascii=False))


if __name__ == "__main__":
    main()
