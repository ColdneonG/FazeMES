import type { Router } from 'vue-router'

/** The hidden route also works before a running installation reloads the new menu. */
export const openCostCenter = (
  router: Router,
  context: { itemId?: number; workorderId?: number; receiptId?: number; salesId?: number }
) =>
  router.push({
    path: router.hasRoute('MesCost') ? '/mes/md/cost' : '/mes/cost-analysis',
    query: context
  })
