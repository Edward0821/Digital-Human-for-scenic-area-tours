<script setup>
/**
 * 权限管理页（第九周老师要求："要有一个页面来管理角色、用户"）。
 * 三个 Tab：①权限资源（菜单/按钮，权限资源=URL）②角色赋权（勾选式全量替换）
 * ③用户管理（角色下拉即改，sys_user_role 与 role 快照双写由后端事务保证）。
 * 仅 ADMIN 可用（路由守卫 + 后端 @SaCheckPermission("perm:manage") 双保险）。
 */
import { onMounted, reactive, ref } from 'vue'
import { http } from '../api/http'

const tab = ref('perms')
const msg = ref('')
const ok = ref(false)

function flash(text, success = true) {
  msg.value = text
  ok.value = success
  setTimeout(() => { msg.value = '' }, 3000)
}

// ---- ① 权限资源 ----
const perms = ref([])
const newPerm = reactive({ permCode: '', permName: '', permType: 2, url: '', parentId: 0 })

async function loadPerms() {
  const { data: res } = await http.get('/permissions')
  perms.value = res.data
}
async function addPerm() {
  try {
    await http.post('/permissions', newPerm)
    flash('权限已新增')
    loadPerms()
  } catch (e) {
    flash(e.response?.data?.message || e.message, false)
  }
}
function typeName(t) { return t === 1 ? '菜单' : '按钮' }

// ---- ② 角色赋权 ----
const roles = ref([])
const roleId = ref(null)
const rolePermIds = ref([])
const saving = ref(false)

async function loadRoles() {
  const { data: res } = await http.get('/roles')
  roles.value = res.data
  if (res.data.length && !roleId.value) roleId.value = res.data[0].id
  await loadRolePerms()
}
async function loadRolePerms() {
  if (!roleId.value) return
  const { data: res } = await http.get(`/roles/${roleId.value}/permissions`)
  rolePermIds.value = res.data
}
async function saveRolePerms() {
  saving.value = true
  try {
    await http.put(`/roles/${roleId.value}/permissions`, { permIds: rolePermIds.value })
    flash('角色权限已保存')
  } catch (e) {
    flash(e.response?.data?.message || e.message, false)
  } finally {
    saving.value = false
  }
}
function togglePerm(id) {
  const i = rolePermIds.value.indexOf(id)
  if (i >= 0) rolePermIds.value.splice(i, 1)
  else rolePermIds.value.push(id)
}

// ---- ③ 用户管理 ----
const users = ref([])

async function loadUsers() {
  const { data: res } = await http.get('/users')
  users.value = res.data
}
async function changeUserRole(user, event) {
  const newRoleId = Number(event.target.value)
  try {
    await http.put(`/users/${user.userId}/roles`, { roleId: newRoleId })
    user.role = roles.value.find((r) => r.id === newRoleId)?.roleCode || user.role
    flash(`${user.username} 的角色已更新为 ${user.role}`)
    loadUsers()
  } catch (e) {
    flash(e.response?.data?.message || e.message, false)
  }
}

onMounted(() => {
  loadPerms()
  loadRoles()
  loadUsers()
})
</script>

<template>
  <div class="perm-view">
    <h2>权限与角色管理（RBAC：用户 → 角色 → 权限资源）</h2>
    <p v-if="msg" :class="ok ? 'banner ok' : 'banner err'">{{ msg }}</p>

    <div class="tabs">
      <button :class="{ active: tab === 'perms' }" @click="tab = 'perms'">① 权限资源</button>
      <button :class="{ active: tab === 'roles' }" @click="tab = 'roles'">② 角色赋权</button>
      <button :class="{ active: tab === 'users' }" @click="tab = 'users'">③ 用户管理</button>
    </div>

    <!-- ① 权限资源 -->
    <section v-if="tab === 'perms'" class="card">
      <h3>权限资源（菜单 / 按钮，权限资源 = URL）</h3>
      <table>
        <thead><tr><th>ID</th><th>权限码</th><th>粒度</th><th>名称</th><th>URL</th><th>父级</th><th>状态</th></tr></thead>
        <tbody>
          <tr v-for="p in perms" :key="p.permId">
            <td>{{ p.permId }}</td>
            <td><code>{{ p.permCode }}</code></td>
            <td>{{ typeName(p.permType) }}</td>
            <td>{{ p.permName }}</td>
            <td class="url">{{ p.url }}</td>
            <td>{{ p.parentId }}</td>
            <td>{{ p.status === 1 ? '启用' : '停用' }}</td>
          </tr>
        </tbody>
      </table>

      <h4>新增权限</h4>
      <div class="form-row">
        <input v-model="newPerm.permCode" placeholder="权限码，如 kb:comment" />
        <input v-model="newPerm.permName" placeholder="名称" />
        <select v-model.number="newPerm.permType">
          <option :value="1">菜单</option>
          <option :value="2">按钮</option>
        </select>
        <input v-model="newPerm.url" placeholder="URL，如 POST /qa/comments" />
        <button @click="addPerm">新增</button>
      </div>
    </section>

    <!-- ② 角色赋权 -->
    <section v-if="tab === 'roles'" class="card">
      <h3>角色赋权（勾选后保存，全量替换）</h3>
      <div class="form-row">
        <select v-model.number="roleId" @change="loadRolePerms">
          <option v-for="r in roles" :key="r.id" :value="r.id">{{ r.roleCode }}（{{ r.roleName }}）</option>
        </select>
        <button :disabled="saving" @click="saveRolePerms">{{ saving ? '保存中…' : '保存该角色权限' }}</button>
      </div>
      <div class="perm-grid">
        <label v-for="p in perms" :key="p.permId" class="perm-item">
          <input type="checkbox" :checked="rolePermIds.includes(p.permId)" @change="togglePerm(p.permId)" />
          <code>{{ p.permCode }}</code>
          <span>{{ p.permName }}</span>
        </label>
      </div>
    </section>

    <!-- ③ 用户管理 -->
    <section v-if="tab === 'users'" class="card">
      <h3>用户管理（改角色即生效：sys_user_role + role 快照双写）</h3>
      <table>
        <thead><tr><th>ID</th><th>用户名</th><th>邮箱</th><th>角色</th><th>注册时间</th></tr></thead>
        <tbody>
          <tr v-for="u in users" :key="u.userId">
            <td>{{ u.userId }}</td>
            <td>{{ u.username }}</td>
            <td>{{ u.email }}</td>
            <td>
              <select :value="roles.find((r) => r.roleCode === u.role)?.id" @change="changeUserRole(u, $event)">
                <option v-for="r in roles" :key="r.id" :value="r.id">{{ r.roleCode }}</option>
              </select>
            </td>
            <td>{{ u.createdAt }}</td>
          </tr>
        </tbody>
      </table>
    </section>
  </div>
</template>

<style scoped>
.perm-view { max-width: 1080px; margin: 0 auto; }
.tabs { display: flex; gap: 8px; margin: 12px 0; }
.tabs button {
  padding: 8px 16px; border: 1px solid #dbe2ee; background: #f6f8fb; border-radius: 6px; cursor: pointer;
}
.tabs button.active { background: #2f6fed; color: #fff; border-color: #2f6fed; }
.card { background: #fff; border: 1px solid #e3e8f0; border-radius: 8px; padding: 16px 20px; margin-bottom: 16px; }
.card h3, .card h4 { margin: 8px 0; }
table { width: 100%; border-collapse: collapse; font-size: 13px; }
th, td { border: 1px solid #e3e8f0; padding: 6px 10px; text-align: left; }
th { background: #f0f4fb; }
.url { font-family: Consolas, monospace; font-size: 12px; color: #556; }
.form-row { display: flex; gap: 8px; flex-wrap: wrap; margin: 8px 0; }
.form-row input, .form-row select { padding: 8px; border: 1px solid #dbe2ee; border-radius: 6px; }
.form-row button { padding: 8px 16px; background: #2f6fed; color: #fff; border: none; border-radius: 6px; cursor: pointer; }
.perm-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(240px, 1fr)); gap: 6px; }
.perm-item { display: flex; align-items: center; gap: 6px; font-size: 13px; padding: 4px 6px; border: 1px solid #eef1f6; border-radius: 6px; }
.banner { padding: 8px 14px; border-radius: 6px; font-size: 13px; }
.banner.ok { background: #e8f7ee; color: #1fa667; }
.banner.err { background: #fdeceb; color: #d93026; }
code { background: #f0f4fb; padding: 1px 5px; border-radius: 4px; }
</style>
