-- Quality quarantine is configuration, never a fabricated stock movement.
-- Existing uncertain stock is held in place. Quantities, batches and historical ledgers are immutable.
-- MySQL 8 client supporting DELIMITER required. Core evidence DML is shared with the H2 regression.
SET NAMES utf8mb4;

CREATE TABLE IF NOT EXISTS mes_wm_quality_config_lock (
  tenant_id BIGINT NOT NULL,
  PRIMARY KEY (tenant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE IF NOT EXISTS mes_wm_stock_quality_hold (
  id BIGINT NOT NULL AUTO_INCREMENT,
  tenant_id BIGINT NOT NULL,
  material_stock_id BIGINT NULL COMMENT 'NULL means evidence cannot safely identify a stock record',
  source_transaction_id BIGINT NOT NULL COMMENT 'Original historical return inbound ledger',
  source_biz_type INT NULL,
  source_biz_id BIGINT NULL,
  source_biz_line_id BIGINT NULL,
  arrival_transaction_id BIGINT NOT NULL COMMENT 'Original inbound or proven paired downstream inbound',
  observed_quantity DECIMAL(20,6) NULL COMMENT 'Observed current total, never a calculated defective quantity',
  reason VARCHAR(500) NOT NULL,
  status VARCHAR(40) NOT NULL DEFAULT 'PENDING_REVIEW',
  create_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  update_time DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  PRIMARY KEY (id),
  UNIQUE KEY uk_quality_hold_evidence (tenant_id, source_transaction_id, arrival_transaction_id),
  KEY idx_quality_hold_stock (tenant_id, material_stock_id),
  KEY idx_quality_hold_status (tenant_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

DROP PROCEDURE IF EXISTS fazemes_quality_quarantine_20261019;
DELIMITER $$
CREATE PROCEDURE fazemes_quality_quarantine_20261019()
BEGIN
  DECLARE migration_lock INT DEFAULT 0;
  DECLARE inserted_rows INT DEFAULT 1;
  DECLARE EXIT HANDLER FOR SQLEXCEPTION
  BEGIN
    ROLLBACK;
    IF migration_lock = 1 THEN DO RELEASE_LOCK(CONCAT(DATABASE(), ':quality-quarantine-v1')); END IF;
    RESIGNAL;
  END;
  SELECT GET_LOCK(CONCAT(DATABASE(), ':quality-quarantine-v1'), 30) INTO migration_lock;
  IF migration_lock IS NULL OR migration_lock <> 1 THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Quality quarantine migration lock unavailable';
  END IF;

  -- DDL deliberately precedes START TRANSACTION: MySQL ALTER TABLE implicitly commits.
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='mes_wm_material_stock' AND column_name='quality_hold') THEN
    ALTER TABLE mes_wm_material_stock ADD COLUMN quality_hold BIT(1) NOT NULL DEFAULT b'0';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns WHERE table_schema=DATABASE()
      AND table_name='mes_wm_material_stock' AND column_name='quality_hold_reason') THEN
    ALTER TABLE mes_wm_material_stock ADD COLUMN quality_hold_reason VARCHAR(500) NULL;
  END IF;

  START TRANSACTION;
  INSERT IGNORE INTO mes_wm_quality_config_lock (tenant_id)
    SELECT id FROM system_tenant WHERE deleted=b'0'
    UNION SELECT tenant_id FROM mes_wm_warehouse;
  -- Serialize with the application's lazy per-tenant configuration initializer.
  UPDATE mes_wm_quality_config_lock SET tenant_id=tenant_id;

  -- Never take over a user warehouse merely because it happens to use a reserved code.
  IF EXISTS (SELECT 1 FROM mes_wm_warehouse WHERE code='QUALITY_QUARANTINE_WAREHOUSE'
      AND (COALESCE(deleted,b'1')<>b'0' OR remark IS NULL OR remark<>'SYSTEM_QUALITY_QUARANTINE'
        OR COALESCE(frozen,b'1')<>b'0'))
    OR EXISTS (SELECT 1 FROM mes_wm_warehouse WHERE code='QUALITY_QUARANTINE_WAREHOUSE'
      GROUP BY tenant_id HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Quality quarantine warehouse reserved identity conflicts';
  END IF;
  INSERT INTO mes_wm_warehouse (code,name,frozen,remark,tenant_id)
    SELECT 'QUALITY_QUARANTINE_WAREHOUSE','不良隔离仓',b'0','SYSTEM_QUALITY_QUARANTINE',t.tenant_id
    FROM mes_wm_quality_config_lock t WHERE NOT EXISTS (SELECT 1 FROM mes_wm_warehouse w
      WHERE w.tenant_id=t.tenant_id AND w.code='QUALITY_QUARANTINE_WAREHOUSE');

  IF EXISTS (SELECT 1 FROM mes_wm_warehouse_location l LEFT JOIN mes_wm_warehouse w
      ON w.id=l.warehouse_id AND w.tenant_id=l.tenant_id
      WHERE l.code='QUALITY_QUARANTINE_LOCATION' AND (COALESCE(l.deleted,b'1')<>b'0' OR l.remark IS NULL
        OR l.remark<>'SYSTEM_QUALITY_QUARANTINE' OR COALESCE(l.frozen,b'1')<>b'0' OR w.id IS NULL
        OR w.code<>'QUALITY_QUARANTINE_WAREHOUSE'))
    OR EXISTS (SELECT 1 FROM mes_wm_warehouse_location WHERE code='QUALITY_QUARANTINE_LOCATION'
      GROUP BY tenant_id HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Quality quarantine location reserved identity conflicts';
  END IF;
  INSERT INTO mes_wm_warehouse_location (code,name,warehouse_id,frozen,remark,tenant_id)
    SELECT 'QUALITY_QUARANTINE_LOCATION','不良隔离库区',w.id,b'0','SYSTEM_QUALITY_QUARANTINE',w.tenant_id
    FROM mes_wm_warehouse w WHERE w.code='QUALITY_QUARANTINE_WAREHOUSE'
      AND NOT EXISTS (SELECT 1 FROM mes_wm_warehouse_location l WHERE l.tenant_id=w.tenant_id
        AND l.code='QUALITY_QUARANTINE_LOCATION');

  IF EXISTS (SELECT 1 FROM mes_wm_warehouse_area a LEFT JOIN mes_wm_warehouse_location l
      ON l.id=a.location_id AND l.tenant_id=a.tenant_id
      WHERE a.code='QUALITY_QUARANTINE_AREA' AND (COALESCE(a.deleted,b'1')<>b'0' OR a.remark IS NULL
        OR a.remark<>'SYSTEM_QUALITY_QUARANTINE' OR COALESCE(a.frozen,b'1')<>b'0' OR COALESCE(a.status,-1)<>0
        OR COALESCE(a.allow_item_mixing,b'0')<>b'1' OR COALESCE(a.allow_batch_mixing,b'0')<>b'1' OR l.id IS NULL
        OR l.code<>'QUALITY_QUARANTINE_LOCATION'))
    OR EXISTS (SELECT 1 FROM mes_wm_warehouse_area WHERE code='QUALITY_QUARANTINE_AREA'
      GROUP BY tenant_id HAVING COUNT(*)>1) THEN
    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'Quality quarantine area reserved identity conflicts';
  END IF;
  INSERT INTO mes_wm_warehouse_area
      (code,name,location_id,status,frozen,allow_item_mixing,allow_batch_mixing,remark,tenant_id)
    SELECT 'QUALITY_QUARANTINE_AREA','不良隔离库位',l.id,0,b'0',b'1',b'1','SYSTEM_QUALITY_QUARANTINE',l.tenant_id
    FROM mes_wm_warehouse_location l WHERE l.code='QUALITY_QUARANTINE_LOCATION'
      AND NOT EXISTS (SELECT 1 FROM mes_wm_warehouse_area a WHERE a.tenant_id=l.tenant_id
        AND a.code='QUALITY_QUARANTINE_AREA');

  -- A deleted line still proves its recorded FAIL result. A header's current status is not
  -- grounds to discard a real ledger. A missing/mismatching source is reported, never guessed.
  -- QUALITY_HISTORY_ROOT_BEGIN
  INSERT IGNORE INTO mes_wm_stock_quality_hold
      (tenant_id,material_stock_id,source_transaction_id,source_biz_type,source_biz_id,
       source_biz_line_id,arrival_transaction_id,observed_quantity,reason,status)
  SELECT t.tenant_id,
      CASE WHEN f.quality_status=2 AND f.parent_id=t.biz_id AND f.item_id=t.item_id
        AND (f.batch_id=t.batch_id OR (f.batch_id IS NULL AND t.batch_id IS NULL))
        AND (rs.id IS NOT NULL OR ri.id IS NOT NULL OR oc.id IS NOT NULL OR ip.id IS NOT NULL) THEN s.id ELSE NULL END,
      t.id,t.biz_type,t.biz_id,t.biz_line_id,t.id,
      CASE WHEN f.quality_status=2 AND f.parent_id=t.biz_id AND f.item_id=t.item_id
        AND (f.batch_id=t.batch_id OR (f.batch_id IS NULL AND t.batch_id IS NULL))
        AND (rs.id IS NOT NULL OR ri.id IS NOT NULL OR oc.id IS NOT NULL OR ip.id IS NOT NULL) THEN s.quantity ELSE NULL END,
      CASE WHEN f.quality_status=2 AND f.parent_id=t.biz_id AND f.item_id=t.item_id
        AND (f.batch_id=t.batch_id OR (f.batch_id IS NULL AND t.batch_id IS NULL))
        AND (rs.id IS NOT NULL OR ri.id IS NOT NULL OR oc.id IS NOT NULL OR ip.id IS NOT NULL) AND s.id IS NOT NULL
        THEN CASE WHEN s.quantity=0 THEN '历史不良混入待核对（当前余量0，已发生出库须核查下游）'
          ELSE '历史不良混入普通仓，当前总库存及下游去向待实物核对；不得据此推算不良数量' END
        ELSE '历史入库来源、检验或库存维度无法可靠对应，待人工核对；未自动冻结其他库存' END,
      'PENDING_REVIEW'
  FROM mes_wm_transaction t
  LEFT JOIN (
      SELECT tenant_id,id AS line_id,119 AS biz_type,return_id AS parent_id,item_id,batch_id,quality_status,0 AS has_iqc
        FROM mes_wm_return_sales_line
      UNION ALL
      SELECT tenant_id,id AS line_id,116 AS biz_type,issue_id AS parent_id,item_id,batch_id,quality_status,0 AS has_iqc
        FROM mes_wm_return_issue_line
      UNION ALL
      SELECT tenant_id,id AS line_id,121 AS biz_type,receipt_id AS parent_id,item_id,batch_id,quality_status,0 AS has_iqc
        FROM mes_wm_outsource_receipt_line
      UNION ALL
      SELECT l.tenant_id,l.id AS line_id,110 AS biz_type,l.receipt_id AS parent_id,l.item_id,l.batch_id,
        CASE WHEN q.status=4 AND q.check_result IN (1,2) AND q.item_id=l.item_id
          AND q.vendor_id=r.vendor_id THEN q.check_result ELSE 0 END AS quality_status,
        CASE WHEN r.iqc_id IS NOT NULL THEN 1 ELSE 0 END AS has_iqc
        FROM mes_wm_item_receipt_line l
        LEFT JOIN mes_wm_item_receipt r ON r.id=l.receipt_id AND r.tenant_id=l.tenant_id
        LEFT JOIN mes_qc_iqc q ON q.id=r.iqc_id AND q.tenant_id=r.tenant_id
  ) f ON f.tenant_id=t.tenant_id AND f.biz_type=t.biz_type AND f.line_id=t.biz_line_id
  LEFT JOIN mes_wm_return_sales rs ON t.biz_type=119 AND rs.id=f.parent_id AND rs.tenant_id=t.tenant_id
  LEFT JOIN mes_wm_return_issue ri ON t.biz_type=116 AND ri.id=f.parent_id AND ri.tenant_id=t.tenant_id
  LEFT JOIN mes_wm_outsource_receipt oc ON t.biz_type=121 AND oc.id=f.parent_id AND oc.tenant_id=t.tenant_id
  LEFT JOIN mes_wm_item_receipt ip ON t.biz_type=110 AND ip.id=t.biz_id AND ip.tenant_id=t.tenant_id
  LEFT JOIN mes_wm_material_stock s ON s.id=t.material_stock_id AND s.tenant_id=t.tenant_id
      AND s.deleted=FALSE AND s.item_id=t.item_id
      AND (s.batch_id=t.batch_id OR (s.batch_id IS NULL AND t.batch_id IS NULL))
      AND s.warehouse_id=t.warehouse_id AND s.location_id=t.location_id AND s.area_id=t.area_id
  WHERE t.deleted=FALSE AND t.biz_type IN (119,116,121,110) AND t.type=1 AND t.quantity>0
      -- Do not classify ordinary uninspected purchases as historical defective receipts.
      AND (t.biz_type<>110 OR ip.iqc_id IS NOT NULL OR f.has_iqc=1)
      AND (f.quality_status IS NULL OR f.quality_status<>1 OR (t.biz_type=110 AND
        (f.parent_id IS NULL OR f.parent_id<>t.biz_id OR f.item_id IS NULL OR f.item_id<>t.item_id
          OR f.batch_id<>t.batch_id OR (f.batch_id IS NULL AND t.batch_id IS NOT NULL)
          OR (f.batch_id IS NOT NULL AND t.batch_id IS NULL) OR s.id IS NULL)))
      AND NOT EXISTS (SELECT 1 FROM mes_wm_warehouse q WHERE q.id=t.warehouse_id
        AND q.tenant_id=t.tenant_id AND q.code='QUALITY_QUARANTINE_WAREHOUSE'
        AND q.remark='SYSTEM_QUALITY_QUARANTINE' AND q.deleted=FALSE);
  -- QUALITY_HISTORY_ROOT_END

  -- Reachability follows actual paired ledgers, not a shared batch or a guessed allocation.
  -- Per-source/per-arrival unique evidence terminates cycles and makes retries idempotent.
  WHILE inserted_rows>0 DO
    -- QUALITY_HISTORY_PROPAGATE_BEGIN
    INSERT IGNORE INTO mes_wm_stock_quality_hold
        (tenant_id,material_stock_id,source_transaction_id,source_biz_type,source_biz_id,
         source_biz_line_id,arrival_transaction_id,observed_quantity,reason,status)
    SELECT DISTINCT h.tenant_id,s.id,
        h.source_transaction_id,h.source_biz_type,h.source_biz_id,h.source_biz_line_id,i.id,
        s.quantity,'历史不良混入库存的后续成对调拨到达，当前总库存待实物核对；未推算不良数量',
        'PENDING_REVIEW'
    FROM mes_wm_stock_quality_hold h
    JOIN mes_wm_transaction a ON a.id=h.arrival_transaction_id AND a.tenant_id=h.tenant_id
        AND a.deleted=FALSE AND a.material_stock_id=h.material_stock_id
    JOIN mes_wm_transaction o ON o.tenant_id=a.tenant_id AND o.material_stock_id=a.material_stock_id
        AND o.deleted=FALSE AND o.type IN (2,3) AND o.quantity<0 AND o.item_id=a.item_id
        AND (o.batch_id=a.batch_id OR (o.batch_id IS NULL AND a.batch_id IS NULL))
        AND o.warehouse_id=a.warehouse_id AND o.location_id=a.location_id AND o.area_id=a.area_id
    JOIN mes_wm_transaction i ON i.tenant_id=o.tenant_id AND i.related_transaction_id=o.id
        AND i.deleted=FALSE AND i.type IN (1,4) AND i.quantity=-o.quantity AND i.item_id=o.item_id
        AND (i.batch_id=o.batch_id OR (i.batch_id IS NULL AND o.batch_id IS NULL))
        AND ((o.type=3 AND i.type=4 AND o.biz_type=111 AND i.biz_type=112)
          OR (o.type=2 AND i.type=1 AND i.biz_type=o.biz_type AND o.biz_type IN (115,116,117)))
        AND i.biz_id=o.biz_id
        AND (i.biz_line_id=o.biz_line_id OR (i.biz_line_id IS NULL AND o.biz_line_id IS NULL))
    LEFT JOIN mes_wm_material_stock s ON s.id=i.material_stock_id AND s.tenant_id=i.tenant_id
        AND s.deleted=FALSE AND s.item_id=i.item_id
        AND (s.batch_id=i.batch_id OR (s.batch_id IS NULL AND i.batch_id IS NULL))
        AND s.warehouse_id=i.warehouse_id AND s.location_id=i.location_id AND s.area_id=i.area_id
    WHERE h.material_stock_id IS NOT NULL AND s.id IS NOT NULL
        AND a.transaction_time IS NOT NULL
        AND (o.transaction_time>a.transaction_time
          OR (o.transaction_time=a.transaction_time AND o.id>a.id))
        AND (i.transaction_time>o.transaction_time
          OR (i.transaction_time=o.transaction_time AND i.id>o.id))
        AND NOT EXISTS (SELECT 1 FROM mes_wm_warehouse q WHERE q.id=i.warehouse_id
          AND q.tenant_id=i.tenant_id AND q.code='QUALITY_QUARANTINE_WAREHOUSE'
          AND q.remark='SYSTEM_QUALITY_QUARANTINE' AND q.deleted=FALSE);
    -- QUALITY_HISTORY_PROPAGATE_END
    SET inserted_rows=ROW_COUNT();
  END WHILE;

  -- Run unresolved evidence only after proven reachability has converged. Otherwise an
  -- uncertain path could occupy the unique key before a later, fully proven path arrives.
  -- An outgoing transfer known to predate the contamination is not a downstream exposure.
  -- QUALITY_HISTORY_UNRESOLVED_BEGIN
  INSERT IGNORE INTO mes_wm_stock_quality_hold
      (tenant_id,material_stock_id,source_transaction_id,source_biz_type,source_biz_id,
       source_biz_line_id,arrival_transaction_id,observed_quantity,reason,status)
  SELECT DISTINCT h.tenant_id,NULL,h.source_transaction_id,h.source_biz_type,h.source_biz_id,
      h.source_biz_line_id,i.id,NULL,
      '历史不良库存存在后续配对到达，但时间或目标库存维度不可靠，待人工核查；未自动冻结其他库存',
      'PENDING_REVIEW'
  FROM mes_wm_stock_quality_hold h
  JOIN mes_wm_transaction a ON a.id=h.arrival_transaction_id AND a.tenant_id=h.tenant_id
      AND a.deleted=FALSE AND a.material_stock_id=h.material_stock_id
  JOIN mes_wm_transaction o ON o.tenant_id=a.tenant_id AND o.material_stock_id=a.material_stock_id
      AND o.deleted=FALSE AND o.type IN (2,3) AND o.quantity<0 AND o.item_id=a.item_id
      AND (o.batch_id=a.batch_id OR (o.batch_id IS NULL AND a.batch_id IS NULL))
      AND o.warehouse_id=a.warehouse_id AND o.location_id=a.location_id AND o.area_id=a.area_id
  JOIN mes_wm_transaction i ON i.tenant_id=o.tenant_id AND i.related_transaction_id=o.id
      AND i.deleted=FALSE AND i.type IN (1,4) AND i.quantity=-o.quantity AND i.item_id=o.item_id
      AND (i.batch_id=o.batch_id OR (i.batch_id IS NULL AND o.batch_id IS NULL))
      AND ((o.type=3 AND i.type=4 AND o.biz_type=111 AND i.biz_type=112)
        OR (o.type=2 AND i.type=1 AND i.biz_type=o.biz_type AND o.biz_type IN (115,116,117)))
      AND i.biz_id=o.biz_id
      AND (i.biz_line_id=o.biz_line_id OR (i.biz_line_id IS NULL AND o.biz_line_id IS NULL))
  WHERE h.material_stock_id IS NOT NULL
      AND (a.transaction_time IS NULL OR o.transaction_time IS NULL
        OR o.transaction_time>a.transaction_time OR (o.transaction_time=a.transaction_time AND o.id>a.id))
      AND NOT EXISTS (SELECT 1 FROM mes_wm_warehouse q WHERE q.id=i.warehouse_id
        AND q.tenant_id=i.tenant_id AND q.code='QUALITY_QUARANTINE_WAREHOUSE'
        AND q.remark='SYSTEM_QUALITY_QUARANTINE' AND q.deleted=FALSE);
  -- QUALITY_HISTORY_UNRESOLVED_END

  -- Preserve the evidence of material already issued/sold, and broken transfer pairs. An
  -- unpaired OUT does not identify any target stock and must not become a batch-wide hold.
  -- QUALITY_HISTORY_UNPAIRED_BEGIN
  INSERT IGNORE INTO mes_wm_stock_quality_hold
      (tenant_id,material_stock_id,source_transaction_id,source_biz_type,source_biz_id,
       source_biz_line_id,arrival_transaction_id,observed_quantity,reason,status)
  SELECT DISTINCT h.tenant_id,NULL,h.source_transaction_id,h.source_biz_type,h.source_biz_id,
      h.source_biz_line_id,o.id,NULL,
      '历史不良库存已有出库，未找到可靠成对到达；销售、消耗或损坏关联须人工核查，未推定目标库存',
      'PENDING_REVIEW'
  FROM mes_wm_stock_quality_hold h
  JOIN mes_wm_transaction a ON a.id=h.arrival_transaction_id AND a.tenant_id=h.tenant_id
      AND a.deleted=FALSE AND a.material_stock_id=h.material_stock_id
  JOIN mes_wm_transaction o ON o.tenant_id=a.tenant_id AND o.material_stock_id=a.material_stock_id
      AND o.deleted=FALSE AND o.type IN (2,3) AND o.quantity<0 AND o.item_id=a.item_id
      AND (o.batch_id=a.batch_id OR (o.batch_id IS NULL AND a.batch_id IS NULL))
      AND o.warehouse_id=a.warehouse_id AND o.location_id=a.location_id AND o.area_id=a.area_id
  WHERE h.material_stock_id IS NOT NULL
      AND (a.transaction_time IS NULL OR o.transaction_time IS NULL
        OR o.transaction_time>a.transaction_time OR (o.transaction_time=a.transaction_time AND o.id>a.id))
      AND NOT EXISTS (SELECT 1 FROM mes_wm_transaction i WHERE i.tenant_id=o.tenant_id
        AND i.related_transaction_id=o.id AND i.deleted=FALSE AND i.type IN (1,4)
        AND i.quantity=-o.quantity AND i.item_id=o.item_id
        AND (i.batch_id=o.batch_id OR (i.batch_id IS NULL AND o.batch_id IS NULL))
        AND ((o.type=3 AND i.type=4 AND o.biz_type=111 AND i.biz_type=112)
          OR (o.type=2 AND i.type=1 AND i.biz_type=o.biz_type AND o.biz_type IN (115,116,117)))
        AND i.biz_id=o.biz_id
        AND (i.biz_line_id=o.biz_line_id OR (i.biz_line_id IS NULL AND o.biz_line_id IS NULL)));
  -- QUALITY_HISTORY_UNPAIRED_END

  -- Hold the exact proven record even at zero: later receipts can reuse the same identity.
  -- An ordinary unfreeze must never clear quality_hold or silently declare an audit resolved.
  -- QUALITY_HISTORY_HOLD_BEGIN
  UPDATE mes_wm_material_stock s
  SET quality_hold=TRUE,frozen=TRUE,quality_hold_reason='历史不良混入待实物及下游核对，禁止普通解冻；详见库存质量核查记录'
  WHERE s.deleted=FALSE AND EXISTS (SELECT 1 FROM mes_wm_stock_quality_hold h
      WHERE h.tenant_id=s.tenant_id AND h.material_stock_id=s.id AND h.status='PENDING_REVIEW');
  -- QUALITY_HISTORY_HOLD_END

  COMMIT;
  DO RELEASE_LOCK(CONCAT(DATABASE(), ':quality-quarantine-v1'));
END$$
DELIMITER ;
CALL fazemes_quality_quarantine_20261019();
DROP PROCEDURE fazemes_quality_quarantine_20261019;
