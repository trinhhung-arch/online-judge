/**
 * Bundled by jsDelivr using Rollup v4.62.2 and esbuild v0.28.1.
 * Original file: /npm/@codemirror/lang-cpp@6.0.2/dist/index.js
 *
 * Do NOT use SRI with dynamically generated files! More information: https://www.jsdelivr.com/using-sri-with-dynamic-files
 */
import{parser as o}from"/vendor/npm/@lezer/cpp@1.0.0/esm.js";import{LRLanguage as a,indentNodeProp as r,flatIndent as i,continuedIndent as t,delimitedIndent as d,foldNodeProp as m,foldInside as p,LanguageSupport as s}from"/vendor/npm/@codemirror/language@6.3.0/esm.js";const n=a.define({name:"cpp",parser:o.configure({props:[r.add({IfStatement:t({except:/^\s*({|else\b)/}),TryStatement:t({except:/^\s*({|catch)\b/}),LabeledStatement:i,CaseStatement:e=>e.baseIndent+e.unit,BlockComment:()=>null,CompoundStatement:d({closing:"}"}),Statement:t({except:/^{/})}),m.add({"DeclarationList CompoundStatement EnumeratorList FieldDeclarationList InitializerList":p,BlockComment(e){return{from:e.from+2,to:e.to-2}}})]}),languageData:{commentTokens:{line:"//",block:{open:"/*",close:"*/"}},indentOnInput:/^\s*(?:case |default:|\{|\})$/,closeBrackets:{stringPrefixes:["L","u","U","u8","LR","UR","uR","u8R","R"]}}});function c(){return new s(n)}export{c as cpp,n as cppLanguage};
