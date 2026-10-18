<template>
  <div class="insight-center">
    <el-card shadow="never" class="intro">
      <div class="intro-line">
        <div
          ><div class="eyebrow">FAZEMES / PRODUCTION INTELLIGENCE</div><h1>生产决策中心</h1
          ><p>发现交付风险，对比处理方案，让每次改善留下依据。</p></div
        >
        <el-button :loading="busy" @click="refresh">刷新当前数据</el-button>
      </div>
      <div class="metrics" v-if="risks">
        <div
          ><small>未结工单</small><strong>{{ risks.summary.open }}</strong></div
        >
        <div
          ><small>高风险工单</small><strong class="danger">{{ risks.summary.high }}</strong></div
        >
        <div
          ><small>交期尚不能估算</small><strong>{{ risks.summary.unknown }}</strong></div
        >
        <div class="metric-note"
          >规则估算 · 实时业务依据<small>更新于 {{ fmt(risks.generatedAt) }}</small></div
        >
      </div>
    </el-card>
    <el-alert v-if="error" :title="error" type="error" show-icon :closable="false" class="notice" />
    <el-card shadow="never" class="content" v-loading="busy">
      <el-tabs v-model="tab">
        <el-tab-pane label="交期风险" name="risks">
          <div class="toolbar"
            ><el-input
              v-model="keyword"
              placeholder="搜索工单编码、名称或产品"
              clearable /><el-select v-model="riskFilter" placeholder="全部风险" clearable
              ><el-option label="高风险" value="HIGH" /><el-option
                label="需关注"
                value="WATCH" /><el-option label="较低风险" value="LOW" /></el-select
          ></div>
          <el-table :data="filteredOrders" empty-text="暂无未结工单" row-key="id">
            <el-table-column type="expand"
              ><template #default="{ row }">
                <div class="expanded"
                  ><h3>{{ row.code }} · 分析依据</h3>
                  <div v-for="r in row.reasons" :key="r.type" class="reason"
                    ><strong>{{ r.message }}</strong
                    ><span>{{ r.action }}</span></div
                  >
                  <p v-if="!row.reasons.length"
                    >当前规则未发现风险；缺料、待检和设备条件仍需随现场情况复核。</p
                  >
                  <el-descriptions :column="2" border
                    ><el-descriptions-item label="未结工序任务">{{
                      row.tasks.length
                    }}</el-descriptions-item
                    ><el-descriptions-item label="未处置安灯">{{
                      row.andons.length
                    }}</el-descriptions-item
                    ><el-descriptions-item label="待完成过程检验">{{
                      row.pendingInspections.length
                    }}</el-descriptions-item
                    ><el-descriptions-item label="队列及前序日历间隔（小时）">{{
                      row.queueCalendarHours
                    }}</el-descriptions-item></el-descriptions
                  >
                  <h4>物料齐套依据</h4
                  ><el-table
                    :data="row.materials"
                    size="small"
                    empty-text="没有工单物料需求，齐套状态未知"
                    ><el-table-column prop="itemName" label="物料" /><el-table-column
                      prop="outstandingQuantity"
                      label="尚需数量" /><el-table-column
                      prop="availableQuantity"
                      label="可用数量" /><el-table-column
                      prop="shortageQuantity"
                      label="缺口" /><el-table-column prop="expectedDate" label="跟进到料日期"
                  /></el-table>
                  <h4>工序任务依据</h4
                  ><el-table :data="row.tasks" size="small" empty-text="工单尚未排产"
                    ><el-table-column prop="code" label="任务编码" /><el-table-column
                      prop="stationName"
                      label="工作站"
                    /><el-table-column prop="processName" label="工序" /><el-table-column
                      prop="remainingMinutes"
                      label="估算剩余分钟"
                    /><el-table-column label="计划开始"
                      ><template #default="s">{{ fmt(s.row.startTime) }}</template></el-table-column
                    ></el-table
                  >
                </div>
              </template></el-table-column
            >
            <el-table-column prop="code" label="工单" min-width="130" /><el-table-column
              prop="productName"
              label="产品"
              min-width="120"
            />
            <el-table-column label="风险" width="90"
              ><template #default="{ row }"
                ><el-tag :type="riskType(row.riskLevel)">{{
                  riskName(row.riskLevel)
                }}</el-tag></template
              ></el-table-column
            >
            <el-table-column label="需求日期" min-width="145"
              ><template #default="{ row }">{{ fmt(row.dueAt) }}</template></el-table-column
            >
            <el-table-column label="规则估算完工" min-width="145"
              ><template #default="{ row }">{{
                row.forecastAt ? fmt(row.forecastAt) : '资料不足，暂不能估算'
              }}</template></el-table-column
            >
            <el-table-column label="原因" min-width="180"
              ><template #default="{ row }">{{
                row.reasons[0]?.message || '当前未发现风险'
              }}</template></el-table-column
            >
            <el-table-column label="操作" width="155" fixed="right"
              ><template #default="{ row }"
                ><el-button link type="primary" @click="openTimeline(row.id)">履历</el-button
                ><el-button
                  v-hasPermi="['mes:insight:simulate']"
                  link
                  type="primary"
                  @click="selectSimulation(row.id)"
                  >仿真</el-button
                ><el-button
                  v-hasPermi="['mes:insight:manage']"
                  link
                  type="primary"
                  @click="newIncident(row)"
                  >跟进</el-button
                ></template
              ></el-table-column
            >
          </el-table>
          <p class="basis">{{ risks?.basis?.replace('任务duration', '排产时长') }}</p
          ><el-alert
            v-for="warning in risks?.warnings"
            :key="warning"
            :title="warning"
            type="warning"
            :closable="false"
            class="notice"
          />
        </el-tab-pane>

        <el-tab-pane label="插单与排产仿真" name="simulation">
          <p class="section-note"
            >用同一批未结任务比较正常队列、选定工单优先和延长日工时。结果供计划评审，正式任务不会被修改。</p
          >
          <el-form :model="simulationInput" label-position="top" class="simulation-form">
            <el-form-item label="优先考虑的工单"
              ><el-select
                v-model="simulationInput.workOrderId"
                filterable
                placeholder="选择未结工单"
                ><el-option
                  v-for="o in risks?.orders"
                  :key="o.id"
                  :label="`${o.code} · ${o.name}`"
                  :value="o.id" /></el-select
            ></el-form-item>
            <el-form-item label="延长后的每日工时"
              ><el-input-number
                v-model="simulationInput.dailyHours"
                :min="8"
                :max="12"
                :precision="0"
            /></el-form-item>
            <el-form-item label="额外小时成本（元/资源小时）"
              ><el-input-number
                v-model="simulationInput.hourlyCost"
                :min="0"
                :max="100000"
                :precision="2"
            /></el-form-item>
            <el-form-item label="无任务工单：估算工作站"
              ><el-select
                v-model="simulationInput.workstationId"
                clearable
                placeholder="已有有效任务时不使用"
                ><el-option
                  v-for="s in risks?.workstations"
                  :key="s.id"
                  :label="s.name"
                  :value="s.id" /></el-select
            ></el-form-item>
            <el-form-item label="无任务工单：估算加工小时"
              ><el-input-number
                v-model="simulationInput.manualHours"
                :min="0.1"
                :max="1000"
                :precision="1"
            /></el-form-item>
            <el-form-item label="计算"
              ><el-button
                v-hasPermi="['mes:insight:simulate']"
                type="primary"
                :disabled="!simulationInput.workOrderId"
                @click="simulate"
                >比较三个方案</el-button
              ></el-form-item
            >
          </el-form>
          <el-alert
            v-if="simulation?.manualEstimate"
            title="选定工单未排产，本次采用你输入的单工位加工工时进行假设计算，未创建真实任务。"
            type="info"
            :closable="false"
          />
          <div class="scenario-grid" v-if="simulation">
            <el-card v-for="s in simulation.scenarios" :key="s.key" shadow="never">
              <h3>{{ s.name }}</h3
              ><p class="finish">{{ fmt(s.selectedFinish) }}</p
              ><p>选定工单估算完工 · 每日 {{ s.dailyHours }} 小时</p>
              <div class="scenario-stats"
                ><span>加班加工 {{ s.overtimeHours }} 小时</span
                ><strong>额外成本 ¥{{ s.addedCost }}</strong></div
              >
              <el-table :data="s.impacts" size="small" empty-text="无受影响工单"
                ><el-table-column prop="code" label="工单" /><el-table-column label="相比正常队列"
                  ><template #default="{ row }"
                    ><span :class="row.shiftHours > 0 ? 'danger' : ''">{{
                      row.shiftHours == null
                        ? '交期未知'
                        : row.shiftHours > 0
                          ? `推迟 ${row.shiftHours} 小时`
                          : row.shiftHours < 0
                            ? `提前 ${-row.shiftHours} 小时`
                            : '不变'
                    }}</span></template
                  ></el-table-column
                ><el-table-column label="交付"
                  ><template #default="{ row }">{{
                    row.forecastAt ? (row.late ? '预计超期' : '未超过交期') : '未知'
                  }}</template></el-table-column
                ></el-table
              >
              <el-collapse
                ><el-collapse-item title="查看资源排队明细" name="slots"
                  ><el-table :data="s.slots" size="small"
                    ><el-table-column prop="name" label="任务" /><el-table-column
                      prop="workstationId"
                      label="工位ID"
                    /><el-table-column label="开始"
                      ><template #default="{ row }">{{
                        fmt(row.startAt)
                      }}</template></el-table-column
                    ><el-table-column label="完成"
                      ><template #default="{ row }">{{ fmt(row.endAt) }}</template></el-table-column
                    ></el-table
                  ></el-collapse-item
                ></el-collapse
              >
            </el-card>
          </div>
          <el-empty v-else description="选择工单并计算，即可查看方案差异" />
          <p class="basis">{{ simulation?.basis }}</p
          ><el-alert
            v-for="w in simulation?.warnings"
            :key="w"
            :title="w"
            type="warning"
            :closable="false"
          />
        </el-tab-pane>

        <el-tab-pane label="质量关联" name="quality">
          <div class="toolbar"
            ><span>分析近</span
            ><el-input-number v-model="days" :min="1" :max="365" :precision="0" /><span
              >天 · 每组数量门槛</span
            ><el-input-number v-model="minSample" :min="1" :max="100000" :precision="0" /><el-button
              @click="loadQuality"
              >重新分析</el-button
            ></div
          >
          <p class="section-note"
            >有效完成报工 {{ quality?.records ?? 0 }} 张；剔除数量不完整记录
            {{ quality?.excluded ?? 0 }} 张。展开每个分组可核对原始报工。</p
          >
          <el-table
            :data="quality?.groups || []"
            empty-text="所选期间暂无有效完成报工，完成真实报工后可分析"
          >
            <el-table-column type="expand"
              ><template #default="{ row }"
                ><div class="expanded"
                  ><el-table :data="row.evidence" size="small"
                    ><el-table-column prop="code" label="报工单" /><el-table-column
                      prop="orderCode"
                      label="工单"
                    /><el-table-column prop="quantity" label="报工数量" /><el-table-column
                      prop="rejected"
                      label="不良数量"
                    /><el-table-column label="报工时间"
                      ><template #default="s">{{ fmt(s.row.at) }}</template></el-table-column
                    ></el-table
                  ></div
                ></template
              ></el-table-column
            >
            <el-table-column prop="dimension" label="关联维度" /><el-table-column
              prop="value"
              label="分组"
              min-width="150"
            /><el-table-column prop="product" label="产品" min-width="130" /><el-table-column
              prop="quantity"
              label="样本数量"
            /><el-table-column prop="records" label="报工单数" /><el-table-column label="不良率"
              ><template #default="{ row }">{{ row.rate }}%</template></el-table-column
            ><el-table-column label="同产品基线"
              ><template #default="{ row }">{{ row.baselineRate }}%</template></el-table-column
            ><el-table-column label="差异（百分点）"
              ><template #default="{ row }"
                ><span :class="row.difference > 0 ? 'danger' : ''"
                  >{{ row.difference > 0 ? '+' : '' }}{{ row.difference }}</span
                ></template
              ></el-table-column
            ><el-table-column label="样本情况"
              ><template #default="{ row }"
                ><el-tag :type="row.sufficient ? 'success' : 'info'">{{
                  row.sufficient ? '达到分析门槛' : '样本不足'
                }}</el-tag></template
              ></el-table-column
            > </el-table
          ><p class="basis">{{ quality?.basis }}</p>
        </el-tab-pane>

        <el-tab-pane label="异常闭环" name="incidents">
          <div class="toolbar"
            ><el-button v-hasPermi="['mes:insight:manage']" type="primary" @click="newIncident()"
              >建立异常跟进</el-button
            ><el-select v-model="caseFilter" clearable placeholder="全部状态"
              ><el-option
                v-for="(label, key) in statusNames"
                :key="key"
                :value="key"
                :label="label" /></el-select
          ></div>
          <el-alert
            v-if="incidentData?.truncated"
            title="仅展示最近500条异常记录"
            type="info"
            :closable="false"
          />
          <el-table :data="filteredCases" empty-text="暂无闭环记录，可从风险工单或安灯建立跟进"
            ><el-table-column prop="id" label="编号" width="80" /><el-table-column
              prop="title"
              label="异常"
              min-width="190"
            /><el-table-column prop="orderCode" label="工单" min-width="140" /><el-table-column
              label="状态"
              width="120"
              ><template #default="{ row }"
                ><el-tag :type="row.status === 'CLOSED' ? 'success' : 'warning'">{{
                  statusNames[row.status]
                }}</el-tag></template
              ></el-table-column
            ><el-table-column prop="ownerName" label="负责人" /><el-table-column label="处理截止"
              ><template #default="{ row }"
                ><span :class="overdue(row) ? 'danger' : ''">{{ fmt(row.dueAt) }}</span></template
              ></el-table-column
            ><el-table-column label="操作" width="155"
              ><template #default="{ row }"
                ><el-button link @click="showHistory(row.id)">履历</el-button
                ><el-button
                  v-hasPermi="['mes:insight:manage']"
                  type="primary"
                  link
                  @click="editIncident(row)"
                  >处理</el-button
                ></template
              ></el-table-column
            ></el-table
          >
          <h3>尚未进入闭环的安灯</h3
          ><el-table :data="incidentData?.andons || []" empty-text="暂无待导入的未处置安灯"
            ><el-table-column prop="id" label="安灯编号" width="110" /><el-table-column
              prop="reason"
              label="呼叫原因"
            /><el-table-column label="操作" width="140"
              ><template #default="{ row }"
                ><el-button
                  v-hasPermi="['mes:insight:manage']"
                  link
                  type="primary"
                  @click="importAndon(row)"
                  >建立闭环</el-button
                ></template
              ></el-table-column
            ></el-table
          >
          <p class="basis"
            >待处理 → 处理中 → 待验证 →
            已关闭。进入验证须填写确认原因与措施，关闭须记录验证通过的证据。验证失败或异常复发可重新处理；所有变更保留履历。关联安灯仅在验证关闭后标记已处置。</p
          >
        </el-tab-pane>

        <el-tab-pane label="实时业务助手" name="assistant">
          <p class="section-note"
            >查询交期、缺料、异常、质量和流程数据。规则答复可直接使用；模型分析复用“AI助手与知识库”的模型配置。</p
          >
          <div class="quick-questions"
            ><el-button v-for="q in questions" :key="q" plain @click="askPreset(q)">{{
              q
            }}</el-button></div
          >
          <el-input
            v-model="question"
            type="textarea"
            :rows="3"
            maxlength="1000"
            show-word-limit
            placeholder="例如：今天哪些工单有交付风险？也可以输入具体工单编码。"
          />
          <div class="toolbar"
            ><el-checkbox v-model="useModel" :disabled="!canModel"
              >调用已配置模型分析上述业务数据</el-checkbox
            ><el-button
              v-hasPermi="['mes:insight:chat']"
              type="primary"
              :disabled="!question.trim()"
              @click="ask"
              >查询业务依据</el-button
            ><el-button @click="router.push('/mes/assistant')">知识库与模型配置</el-button></div
          >
          <div v-if="answer" class="answer"
            ><el-tag>{{
              answer.mode === 'MODEL' ? '模型分析 · 请核对业务依据' : '业务规则答复'
            }}</el-tag
            ><p class="answer-text">{{ answer.answer }}</p
            ><small>查询时间 {{ fmt(answer.generatedAt) }}</small>
            <el-collapse
              ><el-collapse-item title="查看本次查询的业务依据" name="evidence">
                <el-table
                  :data="answerRows"
                  max-height="360"
                  size="small"
                  empty-text="当前范围没有符合条件的业务记录"
                >
                  <el-table-column
                    v-if="answer.topic === 'risks'"
                    prop="code"
                    label="工单编码"
                    min-width="150"
                  />
                  <el-table-column
                    v-if="answer.topic === 'risks'"
                    prop="productName"
                    label="产品"
                    min-width="140"
                  />
                  <el-table-column v-if="answer.topic === 'risks'" label="估算完工" min-width="160"
                    ><template #default="{ row }">{{
                      row.forecastAt ? fmt(row.forecastAt) : '资料不足，暂不能估算'
                    }}</template></el-table-column
                  >
                  <el-table-column v-if="answer.topic === 'risks'" label="主要依据" min-width="220"
                    ><template #default="{ row }">{{
                      row.reasons?.map((r) => r.message).join('；') || '当前未发现风险'
                    }}</template></el-table-column
                  >
                  <el-table-column
                    v-if="answer.topic === 'quality'"
                    prop="dimension"
                    label="维度"
                  />
                  <el-table-column v-if="answer.topic === 'quality'" prop="value" label="分组" />
                  <el-table-column v-if="answer.topic === 'quality'" prop="product" label="产品" />
                  <el-table-column v-if="answer.topic === 'quality'" label="不良率"
                    ><template #default="{ row }">{{ row.rate }}%</template></el-table-column
                  >
                  <el-table-column
                    v-if="answer.topic === 'quality'"
                    prop="quantity"
                    label="样本数量"
                  />
                  <el-table-column v-if="answer.topic === 'flow'" prop="station" label="工作站" />
                  <el-table-column v-if="answer.topic === 'flow'" prop="tasks" label="任务数" />
                  <el-table-column v-if="answer.topic === 'flow'" prop="pending" label="未完成" />
                  <el-table-column v-if="answer.topic === 'flow'" label="计划至首报间隔中位数"
                    ><template #default="{ row }">{{
                      row.medianWaitHours == null ? '尚无有效样本' : `${row.medianWaitHours} 小时`
                    }}</template></el-table-column
                  >
                  <el-table-column
                    v-if="answer.topic === 'incidents'"
                    prop="title"
                    label="异常"
                    min-width="180"
                  />
                  <el-table-column
                    v-if="answer.topic === 'incidents'"
                    prop="ownerName"
                    label="负责人"
                  />
                  <el-table-column v-if="answer.topic === 'incidents'" label="状态"
                    ><template #default="{ row }">{{
                      statusNames[row.status]
                    }}</template></el-table-column
                  >
                  <el-table-column v-if="answer.topic === 'incidents'" label="处理截止"
                    ><template #default="{ row }">{{ fmt(row.dueAt) }}</template></el-table-column
                  >
                </el-table>
                <el-button link type="primary" @click="showEvidence"
                  >前往分析页查看原始记录</el-button
                >
              </el-collapse-item></el-collapse
            >
            <div v-for="suggestion in answer.suggestions" :key="suggestion.title" class="suggestion"
              ><div
                ><strong>{{ suggestion.title }}</strong
                ><p>{{ suggestion.content }}</p></div
              ><el-button v-hasPermi="['mes:insight:manage']" @click="editDraft(suggestion)"
                >编辑并保存建议草稿</el-button
              ></div
            >
          </div>
          <h3>我的建议草稿</h3
          ><el-table :data="drafts" empty-text="暂无建议草稿"
            ><el-table-column prop="title" label="建议" min-width="180" /><el-table-column
              prop="content"
              label="内容"
              min-width="240"
              show-overflow-tooltip
            /><el-table-column label="状态"
              ><template #default="{ row }">{{
                row.status === 'CONVERTED' ? `已转异常 #${row.incidentId}` : '待确认草稿'
              }}</template></el-table-column
            ><el-table-column label="操作" width="150"
              ><template #default="{ row }"
                ><el-button
                  v-hasPermi="['mes:insight:manage']"
                  type="primary"
                  link
                  :disabled="row.status === 'CONVERTED'"
                  @click="convertDraft(row.id)"
                  >确认转为异常跟进</el-button
                ></template
              ></el-table-column
            ></el-table
          >
        </el-tab-pane>

        <el-tab-pane label="流程瓶颈" name="flow">
          <div class="toolbar"
            ><span>分析近</span
            ><el-input-number v-model="flowDays" :min="1" :max="365" :precision="0" /><span>天</span
            ><el-button @click="loadFlow">重新分析</el-button></div
          >
          <el-table :data="flow?.stations || []" empty-text="所选期间暂无工序任务"
            ><el-table-column type="expand"
              ><template #default="{ row }"
                ><div class="expanded"
                  ><el-table :data="row.evidence" size="small"
                    ><el-table-column prop="code" label="任务编码" /><el-table-column
                      label="计划启动"
                      ><template #default="s">{{ fmt(s.row.plannedAt) }}</template></el-table-column
                    ><el-table-column label="首次完成报工"
                      ><template #default="s">{{
                        fmt(s.row.firstReport)
                      }}</template></el-table-column
                    ><el-table-column label="工单履历"
                      ><template #default="s"
                        ><el-button link type="primary" @click="openTimeline(s.row.workOrderId)"
                          >查看事件</el-button
                        ></template
                      ></el-table-column
                    ></el-table
                  ></div
                ></template
              ></el-table-column
            ><el-table-column prop="station" label="工作站" /><el-table-column
              prop="tasks"
              label="任务数"
            /><el-table-column prop="pending" label="未完成" /><el-table-column
              prop="samples"
              label="有首报样本"
            /><el-table-column label="计划至首报间隔中位数"
              ><template #default="{ row }">{{
                row.medianWaitHours == null ? '尚无有效样本' : `${row.medianWaitHours} 小时`
              }}</template></el-table-column
            ></el-table
          >
          <h3>实际报工流转路径</h3
          ><div class="path-grid"
            ><div v-for="(p, i) in flow?.paths || []" :key="i" class="path"
              ><strong>{{ p.from || '未记录工序' }} → {{ p.to || '未记录工序' }}</strong
              ><span>{{ p.count }} 次流转 · 平均报工间隔 {{ p.averageGapHours }} 小时</span
              ><el-button
                v-for="orderId in p.orders.slice(0, 5)"
                :key="orderId"
                link
                type="primary"
                @click="openTimeline(orderId)"
                >工单 #{{ orderId }} 履历</el-button
              ></div
            ></div
          ><el-empty v-if="!flow?.paths.length" description="暂无跨工序的完成报工路径" /><p
            class="basis"
            >{{ flow?.basis }}</p
          >
        </el-tab-pane>
      </el-tabs>
    </el-card>

    <el-drawer
      v-model="timelineOpen"
      :title="`${timeline?.orderCode || '工单'} · 事件履历`"
      size="min(650px,95vw)"
      ><p class="basis">{{ timeline?.basis }}</p
      ><el-timeline
        ><el-timeline-item v-for="(e, i) in timeline?.events || []" :key="i" :timestamp="fmt(e.at)"
          ><strong>{{ e.type }}</strong
          ><p>{{ e.label }} · 记录 #{{ e.id }}</p
          ><p v-if="e.quantity != null"
            >报工 {{ e.quantity }} · 不良 {{ e.rejected ?? 0 }}</p
          ></el-timeline-item
        ></el-timeline
      ><el-empty v-if="!timeline?.events.length" description="暂无可展示事件"
    /></el-drawer>
    <el-drawer v-model="historyOpen" title="异常处理履历" size="min(650px,95vw)"
      ><el-timeline
        ><el-timeline-item
          v-for="event in history"
          :key="String(event.id)"
          :timestamp="fmt(event.at)"
          ><strong
            >{{ statusNames[String(event.fromStatus)] || '创建' }} →
            {{ statusNames[String(event.toStatus)] }}</strong
          ><p>{{ event.note }}</p
          ><p>操作人：{{ event.actorName || event.actorId }}</p
          ><p v-if="event.rootCause">确认原因：{{ event.rootCause }}</p
          ><p v-if="event.correctiveAction">处理措施：{{ event.correctiveAction }}</p
          ><p v-if="event.evidence">验证证据：{{ event.evidence }}</p></el-timeline-item
        ></el-timeline
      ></el-drawer
    >
    <el-dialog v-model="createDialog" title="建立异常跟进" width="min(650px,95vw)"
      ><el-form label-position="top"
        ><el-form-item label="异常标题" required
          ><el-input v-model="incidentForm.title" maxlength="160" /></el-form-item
        ><el-form-item label="分类"
          ><el-select v-model="incidentForm.category"
            ><el-option
              v-for="(label, key) in categories"
              :key="key"
              :value="key"
              :label="label" /></el-select></el-form-item
        ><el-form-item label="关联工单"
          ><el-select
            v-model="incidentForm.workOrderId"
            clearable
            filterable
            :disabled="!!incidentForm.andonId"
            ><el-option
              v-for="o in risks?.orders"
              :key="o.id"
              :value="o.id"
              :label="o.code" /></el-select></el-form-item
        ><el-form-item label="负责人"
          ><el-select v-model="incidentForm.ownerId" clearable
            ><el-option
              v-for="o in incidentData?.owners || []"
              :key="o.id"
              :value="o.id"
              :label="o.name" /></el-select></el-form-item
        ><el-form-item label="处理截止"
          ><el-date-picker
            v-model="incidentForm.dueAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item
        ><el-form-item label="问题描述"
          ><el-input
            v-model="incidentForm.description"
            type="textarea"
            :rows="4"
            maxlength="2000" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="createDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="saveIncident"
          >建立跟进</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="editDialog" title="处理与验证异常" width="min(700px,95vw)"
      ><el-form label-position="top"
        ><el-form-item label="状态"
          ><el-select v-model="moveForm.status"
            ><el-option
              v-for="s in allowedStates"
              :key="s"
              :label="statusNames[s]"
              :value="s" /></el-select></el-form-item
        ><el-form-item label="负责人"
          ><el-select v-model="moveForm.ownerId" clearable
            ><el-option
              v-for="o in incidentData?.owners || []"
              :key="o.id"
              :value="o.id"
              :label="o.name" /></el-select></el-form-item
        ><el-form-item label="处理截止"
          ><el-date-picker
            v-model="moveForm.dueAt"
            type="datetime"
            value-format="YYYY-MM-DDTHH:mm:ss" /></el-form-item
        ><el-form-item label="确认原因（进入验证前必填）"
          ><el-input v-model="moveForm.rootCause" type="textarea" maxlength="2000" /></el-form-item
        ><el-form-item label="处理措施（进入验证前必填）"
          ><el-input
            v-model="moveForm.correctiveAction"
            type="textarea"
            maxlength="2000" /></el-form-item
        ><el-form-item label="验证证据（确认验证通过后填写，关闭必填）"
          ><el-input v-model="moveForm.evidence" type="textarea" maxlength="2000" /></el-form-item
        ><el-form-item label="本次处理说明" required
          ><el-input
            v-model="moveForm.note"
            type="textarea"
            maxlength="2000"
            placeholder="记录本次处理、验证失败或异常复发的情况" /></el-form-item></el-form
      ><template #footer
        ><el-button @click="editDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="moveIncident"
          >保存本次处理</el-button
        ></template
      ></el-dialog
    >
    <el-dialog v-model="draftDialog" title="编辑建议草稿" width="min(650px,95vw)"
      ><el-input v-model="draftForm.title" maxlength="160" placeholder="建议标题" /><el-input
        v-model="draftForm.content"
        type="textarea"
        :rows="8"
        maxlength="4000"
        class="notice"
      /><p class="basis">保存草稿不会修改生产数据；后续可明确确认转为异常跟进。</p
      ><template #footer
        ><el-button @click="draftDialog = false">取消</el-button
        ><el-button type="primary" :loading="busy" @click="saveDraft">保存草稿</el-button></template
      ></el-dialog
    >
  </div>
</template>

<script setup lang="ts">
import {
  InsightApi,
  type RiskData,
  type RiskOrder,
  type SimulationInput,
  type SimulationData,
  type QualityData,
  type FlowData,
  type IncidentData,
  type Incident,
  type IncidentInput,
  type IncidentMove,
  type Answer,
  type Draft
} from '@/api/mes/insight'
import { checkPermi } from '@/utils/permission'
import { ElMessage } from 'element-plus'
defineOptions({ name: 'MesInsight' })
const router = useRouter(),
  route = useRoute()
const tabs = ['risks', 'simulation', 'quality', 'incidents', 'assistant', 'flow']
const tab = ref(tabs.includes(String(route.query.tab)) ? String(route.query.tab) : 'risks')
const busy = ref(false),
  error = ref(''),
  keyword = ref(''),
  riskFilter = ref(''),
  caseFilter = ref('')
const risks = ref<RiskData>(),
  simulation = ref<SimulationData>(),
  quality = ref<QualityData>(),
  flow = ref<FlowData>(),
  incidentData = ref<IncidentData>(),
  answer = ref<Answer>()
const days = ref(30),
  minSample = ref(30),
  flowDays = ref(30),
  question = ref(''),
  useModel = ref(false),
  drafts = ref<Draft[]>([])
const canModel = computed(() => checkPermi(['mes:assistant:chat']))
const answerRows = computed(() => {
  const key =
    answer.value?.topic === 'risks'
      ? 'orders'
      : answer.value?.topic === 'quality'
        ? 'groups'
        : answer.value?.topic === 'flow'
          ? 'stations'
          : 'cases'
  return (answer.value?.evidence[key] || []) as Record<string, unknown>[]
})
function showEvidence() {
  if (answer.value && tabs.includes(answer.value.topic)) tab.value = answer.value.topic
}
const questions = [
  '今天哪些工单有交付风险？',
  '当前缺料情况如何？',
  '有哪些待处理异常？',
  '近30天质量有哪些关联差异？',
  '哪些工作站可能存在流程瓶颈？'
]
const statusNames: Record<string, string> = {
  OPEN: '待处理',
  IN_PROGRESS: '处理中',
  VERIFYING: '待验证',
  CLOSED: '已关闭'
}
const categories: Record<string, string> = {
  DELIVERY: '交期',
  MATERIAL: '物料',
  QUALITY: '质量',
  DEVICE: '设备',
  PROCESS: '工艺流程',
  OTHER: '其他'
}
const fmt = (value: unknown) => (value ? String(value).replace('T', ' ').slice(0, 19) : '—')
const riskName = (value: string) =>
  ({ HIGH: '高风险', WATCH: '需关注', LOW: '较低风险' })[value] || value
const riskType = (value: string): 'danger' | 'warning' | 'success' =>
  value === 'HIGH' ? 'danger' : value === 'WATCH' ? 'warning' : 'success'
const overdue = (row: Incident) =>
  row.status !== 'CLOSED' && !!row.dueAt && new Date(row.dueAt).getTime() < Date.now()
const filteredOrders = computed(() =>
  (risks.value?.orders || []).filter(
    (o) =>
      (!riskFilter.value || o.riskLevel === riskFilter.value) &&
      `${o.code} ${o.name} ${o.productName}`.toLowerCase().includes(keyword.value.toLowerCase())
  )
)
const filteredCases = computed(() =>
  (incidentData.value?.cases || []).filter(
    (c) => !caseFilter.value || c.status === caseFilter.value
  )
)
const simulationInput = reactive<SimulationInput>({
  workOrderId: undefined,
  dailyHours: 10,
  hourlyCost: 80,
  workstationId: undefined,
  manualHours: 4
})
watch(
  simulationInput,
  () => {
    simulation.value = undefined
  },
  { deep: true }
)
async function run(action: () => Promise<void>) {
  busy.value = true
  error.value = ''
  try {
    await action()
  } catch (e) {
    error.value =
      e instanceof Error ? e.message : typeof e === 'string' ? e : '操作失败，请检查提示后重试'
  } finally {
    busy.value = false
  }
}
async function loadRisks() {
  risks.value = await InsightApi.risks()
}
async function loadQuality() {
  await run(async () => {
    quality.value = await InsightApi.quality(days.value, minSample.value)
  })
}
async function loadFlow() {
  await run(async () => {
    flow.value = await InsightApi.flow(flowDays.value)
  })
}
async function loadIncidents() {
  incidentData.value = await InsightApi.incidents()
}
async function refresh() {
  await run(async () => {
    if (tab.value === 'risks' || tab.value === 'simulation') await loadRisks()
    else if (tab.value === 'quality')
      quality.value = await InsightApi.quality(days.value, minSample.value)
    else if (tab.value === 'flow') flow.value = await InsightApi.flow(flowDays.value)
    else if (tab.value === 'incidents') await loadIncidents()
    else drafts.value = await InsightApi.drafts()
  })
}
watch(tab, () => {
  void refresh()
})
function selectSimulation(id: number) {
  simulationInput.workOrderId = id
  tab.value = 'simulation'
}
async function simulate() {
  simulation.value = undefined
  await run(async () => {
    simulation.value = await InsightApi.simulate({ ...simulationInput })
  })
}
const timelineOpen = ref(false),
  historyOpen = ref(false),
  createDialog = ref(false),
  editDialog = ref(false),
  draftDialog = ref(false)
const timeline = ref<Awaited<ReturnType<typeof InsightApi.timeline>>>(),
  history = ref<Record<string, unknown>[]>([])
async function openTimeline(id: number) {
  await run(async () => {
    timeline.value = await InsightApi.timeline(id)
    timelineOpen.value = true
  })
}
async function showHistory(id: number) {
  await run(async () => {
    history.value = await InsightApi.history(id)
    historyOpen.value = true
  })
}
const incidentForm = ref<IncidentInput>({ title: '', category: 'OTHER', description: '' })
async function newIncident(order?: RiskOrder) {
  await run(async () => {
    await loadIncidents()
    incidentForm.value = {
      title: order ? `跟进工单 ${order.code}` : '',
      category: order ? 'DELIVERY' : 'OTHER',
      workOrderId: order?.id,
      description: order?.reasons.map((r) => `${r.message}；建议：${r.action}`).join('\n') || ''
    }
    createDialog.value = true
  })
}
async function importAndon(row: NonNullable<IncidentData['andons']>[number]) {
  await run(async () => {
    await loadIncidents()
    incidentForm.value = {
      title: `安灯 #${row.id} · ${row.reason}`.slice(0, 160),
      category: 'OTHER',
      andonId: row.id,
      workOrderId: row.workOrderId,
      workstationId: row.workstationId,
      description: row.reason
    }
    createDialog.value = true
  })
}
async function saveIncident() {
  if (!incidentForm.value.title.trim()) return ElMessage.warning('请填写异常标题')
  await run(async () => {
    await InsightApi.createIncident(incidentForm.value)
    createDialog.value = false
    await loadIncidents()
    tab.value = 'incidents'
    ElMessage.success('已建立异常跟进')
  })
}
const originalStatus = ref('OPEN'),
  moveForm = ref<IncidentMove>({
    id: 0,
    version: 0,
    status: 'OPEN',
    rootCause: '',
    correctiveAction: '',
    evidence: '',
    note: ''
  })
const allowedStates = computed(
  () =>
    ({
      OPEN: ['OPEN', 'IN_PROGRESS'],
      IN_PROGRESS: ['IN_PROGRESS', 'VERIFYING'],
      VERIFYING: ['VERIFYING', 'CLOSED', 'IN_PROGRESS'],
      CLOSED: ['IN_PROGRESS']
    })[originalStatus.value] || []
)
function editIncident(row: Incident) {
  originalStatus.value = row.status
  moveForm.value = {
    id: row.id,
    version: row.version,
    status: row.status === 'CLOSED' ? 'IN_PROGRESS' : row.status,
    ownerId: row.ownerId,
    dueAt: row.dueAt,
    rootCause: row.rootCause || '',
    correctiveAction: row.correctiveAction || '',
    evidence: row.evidence || '',
    note: ''
  }
  editDialog.value = true
}
async function moveIncident() {
  if (!moveForm.value.note.trim()) return ElMessage.warning('请填写本次处理说明')
  await run(async () => {
    await InsightApi.moveIncident(moveForm.value)
    editDialog.value = false
    await loadIncidents()
    await loadRisks()
    ElMessage.success('处理记录已保存')
  })
}
async function ask() {
  if (!question.value.trim()) return
  answer.value = undefined
  await run(async () => {
    answer.value = await InsightApi.ask(question.value, useModel.value)
  })
}
function askPreset(value: string) {
  question.value = value
  void ask()
}
function editDraft(value: Draft) {
  draftForm.value = { ...value }
  draftDialog.value = true
}
const draftForm = ref<Draft>({ id: 0, title: '', content: '' })
async function saveDraft() {
  if (!draftForm.value.title.trim() || !draftForm.value.content.trim())
    return ElMessage.warning('请填写标题与建议内容')
  await run(async () => {
    await InsightApi.saveDraft(draftForm.value)
    draftDialog.value = false
    drafts.value = await InsightApi.drafts()
    ElMessage.success('建议草稿已保存')
  })
}
async function convertDraft(id: number) {
  await run(async () => {
    const caseId = await InsightApi.convertDraft(id)
    drafts.value = await InsightApi.drafts()
    ElMessage.success(`已转为异常跟进 #${caseId}`)
  })
}
onMounted(async () => {
  await run(async () => {
    await loadRisks()
    if (tab.value === 'quality')
      quality.value = await InsightApi.quality(days.value, minSample.value)
    if (tab.value === 'flow') flow.value = await InsightApi.flow(flowDays.value)
    if (tab.value === 'incidents') await loadIncidents()
    if (tab.value === 'assistant') drafts.value = await InsightApi.drafts()
  })
})
</script>

<style scoped lang="scss">
.insight-center {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.intro-line {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  flex-wrap: wrap;
}

.eyebrow {
  font-size: 11px;
  letter-spacing: 1.4px;
  color: #368a96;
}

h1 {
  margin: 10px 0;
  font-size: 26px;
  letter-spacing: -0.5px;
}

h3 {
  margin: 20px 0 12px;
  font-size: 16px;
}

p {
  line-height: 1.8;
  color: var(--el-text-color-regular);
}

.intro p {
  margin: 0;
}

.metrics {
  display: grid;
  padding-top: 22px;
  margin-top: 22px;
  border-top: 1px solid var(--el-border-color-lighter);
  grid-template-columns: repeat(3, 1fr) 1.5fr;
  gap: 20px;
}

.metrics small {
  display: block;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.metrics strong {
  display: block;
  margin-top: 6px;
  font-size: 30px;
  font-weight: 600;
}

.metric-note {
  align-self: center;
  font-size: 13px;
  line-height: 2;
}

.danger {
  color: var(--el-color-danger);
}

.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin: 12px 0 20px;
}

.toolbar .el-input {
  max-width: 330px;
}

.toolbar .el-select {
  width: 170px;
}

.basis,
.section-note {
  font-size: 13px;
  line-height: 1.9;
  color: var(--el-text-color-secondary);
}

.basis {
  padding: 12px 16px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.expanded {
  padding: 8px 24px 20px;
}

.reason {
  display: flex;
  padding: 10px 0;
  margin-bottom: 10px;
  border-bottom: 1px solid var(--el-border-color-lighter);
  flex-direction: column;
  gap: 4px;
}

.reason span {
  color: var(--el-text-color-secondary);
}

.simulation-form {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 0 20px;
}

.simulation-form .el-select {
  width: 100%;
}

.scenario-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 20px;
}

.scenario-grid h3 {
  margin-top: 0;
}

.scenario-grid p {
  font-size: 12px;
}

.scenario-grid .finish {
  font-size: 19px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}

.scenario-stats {
  display: flex;
  padding: 12px 0 18px;
  font-size: 12px;
  flex-wrap: wrap;
  gap: 8px;
  justify-content: space-between;
}

.quick-questions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 16px 0;
}

.quick-questions .el-button {
  margin-left: 0;
}

.answer {
  padding: 20px;
  background: var(--el-fill-color-extra-light);
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.answer-text,
.suggestion p {
  white-space: pre-wrap;
}

.answer small {
  color: var(--el-text-color-secondary);
}

.evidence {
  max-height: 450px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

.suggestion {
  display: flex;
  padding-top: 16px;
  margin-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
  align-items: center;
  gap: 16px;
  justify-content: space-between;
}

.suggestion p {
  font-size: 12px;
}

.notice {
  margin: 12px 0;
}

.path-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 14px;
}

.path {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 12px;
  padding: 18px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 8px;
}

.path span {
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

@media (width <= 1200px) {
  .scenario-grid {
    grid-template-columns: 1fr;
  }
}

@media (width <= 700px) {
  .metrics {
    grid-template-columns: repeat(2, 1fr);
  }

  .simulation-form {
    grid-template-columns: 1fr;
  }

  .suggestion {
    align-items: flex-start;
    flex-direction: column;
  }

  .expanded {
    padding: 8px;
  }
}
</style>
