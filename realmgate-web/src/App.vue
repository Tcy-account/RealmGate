<script setup lang="ts">
// 根组件：只负责渲染路由出口。
// 所有页面（Login / Dashboard / Projects ...）由 router 决定。
</script>

<template>
  <router-view />
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}
</style>
