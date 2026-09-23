/**
 * Bundled by jsDelivr using Rollup v4.62.2 and esbuild v0.28.1.
 * Original file: /npm/@codemirror/lang-java@6.0.1/dist/index.js
 *
 * Do NOT use SRI with dynamically generated files! More information: https://www.jsdelivr.com/using-sri-with-dynamic-files
 */
import{parser as l}from"/vendor/npm/@lezer/java@1.0.0/esm.js";import{LRLanguage as d,indentNodeProp as s,flatIndent as c,continuedIndent as t,delimitedIndent as i,foldNodeProp as m,foldInside as u,LanguageSupport as f}from"/vendor/npm/@codemirror/language@6.3.0/esm.js";const a=d.define({name:"java",parser:l.configure({props:[s.add({IfStatement:t({except:/^\s*({|else\b)/}),TryStatement:t({except:/^\s*({|catch|finally)\b/}),LabeledStatement:c,SwitchBlock:e=>{let n=e.textAfter,o=/^\s*\}/.test(n),r=/^\s*(case|default)\b/.test(n);return e.baseIndent+(o?0:r?1:2)*e.unit},Block:i({closing:"}"}),BlockComment:()=>null,Statement:t({except:/^{/})}),m.add({"Block SwitchBlock ClassBody ElementValueArrayInitializer ModuleBody EnumBody ConstructorBody InterfaceBody ArrayInitializer":u,BlockComment(e){return{from:e.from+2,to:e.to-2}}})]}),languageData:{commentTokens:{line:"//",block:{open:"/*",close:"*/"}},indentOnInput:/^\s*(?:case |default:|\{|\})$/}});function p(){return new f(a)}export{p as java,a as javaLanguage};
