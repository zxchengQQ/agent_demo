import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './App.vue';
import './styles/global.css';

// 应用入口
// 业务含义：创建 Vue 应用，注册 Pinia 状态管理，导入全局样式，挂载到 #app
const app = createApp(App);
app.use(createPinia());
app.mount('#app');
