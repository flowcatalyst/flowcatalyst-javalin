import{X as _,a0 as $,o as r,g as u,ae as S,m as V,i as N,w as x,y as l,p as w,h as s,q as m,l as h,K as j,j as q,r as y,c,z,T as H,_ as K}from"./index-vE2h-lD0.js";import{s as L}from"./index-BaOEDKHG.js";import{s as O}from"./index-DqMxzk4h.js";var U=`
    .p-inputgroup,
    .p-inputgroup .p-iconfield,
    .p-inputgroup .p-floatlabel,
    .p-inputgroup .p-iftalabel {
        display: flex;
        align-items: stretch;
        width: 100%;
    }

    .p-inputgroup .p-floatlabel .p-inputwrapper,
    .p-inputgroup .p-iftalabel .p-inputwrapper {
        display: inline-flex;
    }

    .p-inputgroup .p-inputtext,
    .p-inputgroup .p-inputwrapper {
        flex: 1 1 auto;
        width: 1%;
    }

    .p-inputgroupaddon {
        display: flex;
        align-items: center;
        justify-content: center;
        padding: dt('inputgroup.addon.padding');
        background: dt('inputgroup.addon.background');
        color: dt('inputgroup.addon.color');
        border-block-start: 1px solid dt('inputgroup.addon.border.color');
        border-block-end: 1px solid dt('inputgroup.addon.border.color');
        min-width: dt('inputgroup.addon.min.width');
    }

    .p-inputgroupaddon:first-child,
    .p-inputgroupaddon + .p-inputgroupaddon {
        border-inline-start: 1px solid dt('inputgroup.addon.border.color');
    }

    .p-inputgroupaddon:last-child {
        border-inline-end: 1px solid dt('inputgroup.addon.border.color');
    }

    .p-inputgroupaddon:has(.p-button) {
        padding: 0;
        overflow: hidden;
    }

    .p-inputgroupaddon .p-button {
        border-radius: 0;
    }

    .p-inputgroup > .p-component,
    .p-inputgroup > .p-inputwrapper > .p-component,
    .p-inputgroup > .p-iconfield > .p-component,
    .p-inputgroup > .p-floatlabel > .p-component,
    .p-inputgroup > .p-floatlabel > .p-inputwrapper > .p-component,
    .p-inputgroup > .p-iftalabel > .p-component,
    .p-inputgroup > .p-iftalabel > .p-inputwrapper > .p-component {
        border-radius: 0;
        margin: 0;
    }

    .p-inputgroupaddon:first-child,
    .p-inputgroup > .p-component:first-child,
    .p-inputgroup > .p-inputwrapper:first-child > .p-component,
    .p-inputgroup > .p-iconfield:first-child > .p-component,
    .p-inputgroup > .p-floatlabel:first-child > .p-component,
    .p-inputgroup > .p-floatlabel:first-child > .p-inputwrapper > .p-component,
    .p-inputgroup > .p-iftalabel:first-child > .p-component,
    .p-inputgroup > .p-iftalabel:first-child > .p-inputwrapper > .p-component {
        border-start-start-radius: dt('inputgroup.addon.border.radius');
        border-end-start-radius: dt('inputgroup.addon.border.radius');
    }

    .p-inputgroupaddon:last-child,
    .p-inputgroup > .p-component:last-child,
    .p-inputgroup > .p-inputwrapper:last-child > .p-component,
    .p-inputgroup > .p-iconfield:last-child > .p-component,
    .p-inputgroup > .p-floatlabel:last-child > .p-component,
    .p-inputgroup > .p-floatlabel:last-child > .p-inputwrapper > .p-component,
    .p-inputgroup > .p-iftalabel:last-child > .p-component,
    .p-inputgroup > .p-iftalabel:last-child > .p-inputwrapper > .p-component {
        border-start-end-radius: dt('inputgroup.addon.border.radius');
        border-end-end-radius: dt('inputgroup.addon.border.radius');
    }

    .p-inputgroup .p-component:focus,
    .p-inputgroup .p-component.p-focus,
    .p-inputgroup .p-inputwrapper-focus,
    .p-inputgroup .p-component:focus ~ label,
    .p-inputgroup .p-component.p-focus ~ label,
    .p-inputgroup .p-inputwrapper-focus ~ label,
    .p-inputgroup .p-floatlabel .p-inputwrapper ~ label,
    .p-inputgroup .p-iftalabel .p-inputwrapper ~ label {
        z-index: 1;
    }

    .p-inputgroup > .p-button:not(.p-button-icon-only) {
        width: auto;
    }

    .p-inputgroup .p-iconfield + .p-iconfield .p-inputtext {
        border-inline-start: 0;
    }
`,X={root:"p-inputgroup"},Y=_.extend({name:"inputgroup",style:U,classes:X}),F={name:"BaseInputGroup",extends:$,style:Y,provide:function(){return{$pcInputGroup:this,$parentInstance:this}}},I={name:"InputGroup",extends:F,inheritAttrs:!1};function J(e,g,a,b,f,t){return r(),u("div",V({class:e.cx("root")},e.ptmi("root")),[S(e.$slots,"default")],16)}I.render=J;var Q={root:"p-inputgroupaddon"},Z=_.extend({name:"inputgroupaddon",classes:Q}),ee={name:"BaseInputGroupAddon",extends:$,style:Z,provide:function(){return{$pcInputGroupAddon:this,$parentInstance:this}}},k={name:"InputGroupAddon",extends:ee,inheritAttrs:!1};function ne(e,g,a,b,f,t){return r(),u("div",V({class:e.cx("root")},e.ptmi("root")),[S(e.$slots,"default")],16)}k.render=ne;const pe={class:"secret-ref-input"},te={key:0,class:"field-label"},oe={class:"input-row"},re={class:"provider-option"},ae={class:"provider-label"},ie={class:"provider-desc"},le={key:1,class:"field-help"},ue={key:2,class:"field-help"},de=N({__name:"SecretRefInput",props:{modelValue:{},label:{},helpText:{},disabled:{type:Boolean}},emits:["update:modelValue","validate"],setup(e,{emit:g}){const a=[{label:"Local Encrypted",value:"encrypt",prefix:"encrypt:",placeholder:"your-client-secret",description:"Encrypt and store locally (requires APP_KEY)"},{label:"AWS Secrets Manager",value:"aws-sm",prefix:"aws-sm://",placeholder:"secret-name",description:"Reference a secret in AWS Secrets Manager"},{label:"AWS Parameter Store",value:"aws-ps",prefix:"aws-ps://",placeholder:"/path/to/parameter",description:"Reference a parameter in AWS SSM Parameter Store"},{label:"GCP Secret Manager",value:"gcp-sm",prefix:"gcp-sm://",placeholder:"secret-name",description:"Reference a secret in Google Cloud Secret Manager"},{label:"HashiCorp Vault",value:"vault",prefix:"vault://",placeholder:"path/to/secret#key",description:"Reference a secret in HashiCorp Vault"}],b=e,f=g,t=y("encrypt"),o=y("");function A(n){if(!n){t.value="encrypt",o.value="";return}for(const p of a)if(n.startsWith(p.prefix)){t.value=p.value,o.value=n.substring(p.prefix.length);return}t.value="encrypt",o.value=n}x(()=>b.modelValue,n=>{A(n)},{immediate:!0});const d=c(()=>{if(!o.value.trim())return"";const n=a.find(p=>p.value===t.value);return n?n.prefix+o.value.trim():o.value.trim()});x(d,n=>{f("update:modelValue",n)});const v=c(()=>a.find(n=>n.value===t.value)),G=c(()=>v.value?.placeholder||""),P=c(()=>v.value?.description||""),B=c(()=>v.value?.prefix||"");function C(){d.value&&f("validate",d.value)}return(n,p)=>{const T=O,M=k,W=L,E=I,R=z,D=H;return r(),u("div",pe,[e.label?(r(),u("label",te,l(e.label),1)):w("",!0),s("div",oe,[m(T,{modelValue:t.value,"onUpdate:modelValue":p[0]||(p[0]=i=>t.value=i),options:a,optionLabel:"label",optionValue:"value",class:"provider-dropdown",disabled:e.disabled},{option:h(({option:i})=>[s("div",re,[s("span",ae,l(i.label),1),s("small",ie,l(i.description),1)])]),_:1},8,["modelValue","disabled"]),m(E,{class:"secret-input-group"},{default:h(()=>[m(M,{class:"prefix-addon"},{default:h(()=>[s("code",null,l(B.value),1)]),_:1}),m(W,{modelValue:o.value,"onUpdate:modelValue":p[1]||(p[1]=i=>o.value=i),placeholder:G.value,disabled:e.disabled,class:"secret-input"},null,8,["modelValue","placeholder","disabled"])]),_:1}),d.value?j((r(),q(R,{key:0,icon:"pi pi-check-circle",severity:"secondary",outlined:"",disabled:e.disabled||!d.value,onClick:C,class:"validate-btn"},null,8,["disabled"])),[[D,"Validate secret reference"]]):w("",!0)]),e.helpText?(r(),u("small",le,l(e.helpText),1)):(r(),u("small",ue,l(P.value),1))])}}}),me=K(de,[["__scopeId","data-v-da435bbb"]]);export{me as _};
