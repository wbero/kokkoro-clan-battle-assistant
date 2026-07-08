"use strict";

function createFloatingWindow() {
  var window = floaty.window(
    <vertical bg="#f5f5dc" padding="4">
      <text id="title" text="可可萝自动会战助手" textSize="14sp" textColor="#222222"/>
      <text id="time" text="时间: --:--" textSize="14sp" textColor="#222222"/>
      <text id="file" text="轴: 未选择" textSize="12sp" textColor="#333333"/>
      <horizontal>
        <button id="start" text="开始" w="55dp"/>
        <button id="pause" text="暂停" w="55dp"/>
        <button id="stop" text="停止" w="55dp"/>
      </horizontal>
      <horizontal>
        <button id="openFile" text="选轴" w="55dp"/>
        <button id="calibrate" text="校准" w="55dp"/>
        <button id="exitBtn" text="关闭" w="55dp"/>
      </horizontal>
      <text id="warning" text="" textSize="12sp" textColor="#aa2222"/>
    </vertical>
  );
  window.exitOnClose();
  return window;
}

module.exports = {
  createFloatingWindow: createFloatingWindow
};
