import{_ as ot}from"./FcFormActions-D_kIayET.js";import{a0 as F,ag as lt,O as at,o as f,g as m,h as i,ai as P,X as it,m as y,p as x,y as S,a2 as M,aj as j,an as q,A as rt,F as st,S as ut,j as U,k as dt,l as V,ar as I,aA as B,i as pt,n as ct,c as C,x as T,q as g,z as gt,r as b,V as bt,_ as ft}from"./index-DGvIXR7g.js";import{s as mt}from"./index-CxI7xje8.js";import{s as vt}from"./index-BZNJsmjz.js";import{b as G,s as yt}from"./index-OmxA_-mU.js";import{a as ht}from"./applications-jwMnIklW.js";import{u as wt,E as Vt}from"./EntityDrawer-BywIWKHn.js";import"./index-D2rXk3V4.js";var St=`
    .p-togglebutton {
        display: inline-flex;
        cursor: pointer;
        user-select: none;
        overflow: hidden;
        position: relative;
        color: dt('togglebutton.color');
        background: dt('togglebutton.background');
        border: 1px solid dt('togglebutton.border.color');
        padding: dt('togglebutton.padding');
        font-size: 1rem;
        font-family: inherit;
        font-feature-settings: inherit;
        transition:
            background dt('togglebutton.transition.duration'),
            color dt('togglebutton.transition.duration'),
            border-color dt('togglebutton.transition.duration'),
            outline-color dt('togglebutton.transition.duration'),
            box-shadow dt('togglebutton.transition.duration');
        border-radius: dt('togglebutton.border.radius');
        outline-color: transparent;
        font-weight: dt('togglebutton.font.weight');
    }

    .p-togglebutton-content {
        display: inline-flex;
        flex: 1 1 auto;
        align-items: center;
        justify-content: center;
        gap: dt('togglebutton.gap');
        padding: dt('togglebutton.content.padding');
        background: transparent;
        border-radius: dt('togglebutton.content.border.radius');
        transition:
            background dt('togglebutton.transition.duration'),
            color dt('togglebutton.transition.duration'),
            border-color dt('togglebutton.transition.duration'),
            outline-color dt('togglebutton.transition.duration'),
            box-shadow dt('togglebutton.transition.duration');
    }

    .p-togglebutton:not(:disabled):not(.p-togglebutton-checked):hover {
        background: dt('togglebutton.hover.background');
        color: dt('togglebutton.hover.color');
    }

    .p-togglebutton.p-togglebutton-checked {
        background: dt('togglebutton.checked.background');
        border-color: dt('togglebutton.checked.border.color');
        color: dt('togglebutton.checked.color');
    }

    .p-togglebutton-checked .p-togglebutton-content {
        background: dt('togglebutton.content.checked.background');
        box-shadow: dt('togglebutton.content.checked.shadow');
    }

    .p-togglebutton:focus-visible {
        box-shadow: dt('togglebutton.focus.ring.shadow');
        outline: dt('togglebutton.focus.ring.width') dt('togglebutton.focus.ring.style') dt('togglebutton.focus.ring.color');
        outline-offset: dt('togglebutton.focus.ring.offset');
    }

    .p-togglebutton.p-invalid {
        border-color: dt('togglebutton.invalid.border.color');
    }

    .p-togglebutton:disabled {
        opacity: 1;
        cursor: default;
        background: dt('togglebutton.disabled.background');
        border-color: dt('togglebutton.disabled.border.color');
        color: dt('togglebutton.disabled.color');
    }

    .p-togglebutton-label,
    .p-togglebutton-icon {
        position: relative;
        transition: none;
    }

    .p-togglebutton-icon {
        color: dt('togglebutton.icon.color');
    }

    .p-togglebutton:not(:disabled):not(.p-togglebutton-checked):hover .p-togglebutton-icon {
        color: dt('togglebutton.icon.hover.color');
    }

    .p-togglebutton.p-togglebutton-checked .p-togglebutton-icon {
        color: dt('togglebutton.icon.checked.color');
    }

    .p-togglebutton:disabled .p-togglebutton-icon {
        color: dt('togglebutton.icon.disabled.color');
    }

    .p-togglebutton-sm {
        padding: dt('togglebutton.sm.padding');
        font-size: dt('togglebutton.sm.font.size');
    }

    .p-togglebutton-sm .p-togglebutton-content {
        padding: dt('togglebutton.content.sm.padding');
    }

    .p-togglebutton-lg {
        padding: dt('togglebutton.lg.padding');
        font-size: dt('togglebutton.lg.font.size');
    }

    .p-togglebutton-lg .p-togglebutton-content {
        padding: dt('togglebutton.content.lg.padding');
    }

    .p-togglebutton-fluid {
        width: 100%;
    }
`,kt={root:function(e){var n=e.instance,a=e.props;return["p-togglebutton p-component",{"p-togglebutton-checked":n.active,"p-invalid":n.$invalid,"p-togglebutton-fluid":a.fluid,"p-togglebutton-sm p-inputfield-sm":a.size==="small","p-togglebutton-lg p-inputfield-lg":a.size==="large"}]},content:"p-togglebutton-content",icon:"p-togglebutton-icon",label:"p-togglebutton-label"},Lt=F.extend({name:"togglebutton",style:St,classes:kt}),Ot={name:"BaseToggleButton",extends:G,props:{onIcon:String,offIcon:String,onLabel:{type:String,default:"Yes"},offLabel:{type:String,default:"No"},readonly:{type:Boolean,default:!1},tabindex:{type:Number,default:null},ariaLabelledby:{type:String,default:null},ariaLabel:{type:String,default:null},size:{type:String,default:null},fluid:{type:Boolean,default:null}},style:Lt,provide:function(){return{$pcToggleButton:this,$parentInstance:this}}};function k(t){"@babel/helpers - typeof";return k=typeof Symbol=="function"&&typeof Symbol.iterator=="symbol"?function(e){return typeof e}:function(e){return e&&typeof Symbol=="function"&&e.constructor===Symbol&&e!==Symbol.prototype?"symbol":typeof e},k(t)}function At(t,e,n){return(e=Bt(e))in t?Object.defineProperty(t,e,{value:n,enumerable:!0,configurable:!0,writable:!0}):t[e]=n,t}function Bt(t){var e=Tt(t,"string");return k(e)=="symbol"?e:e+""}function Tt(t,e){if(k(t)!="object"||!t)return t;var n=t[Symbol.toPrimitive];if(n!==void 0){var a=n.call(t,e);if(k(a)!="object")return a;throw new TypeError("@@toPrimitive must return a primitive value.")}return(e==="string"?String:Number)(t)}var H={name:"ToggleButton",extends:Ot,inheritAttrs:!1,emits:["change"],methods:{getPTOptions:function(e){var n=e==="root"?this.ptmi:this.ptm;return n(e,{context:{active:this.active,disabled:this.disabled}})},onChange:function(e){!this.disabled&&!this.readonly&&(this.writeValue(!this.d_value,e),this.$emit("change",e))},onBlur:function(e){var n,a;(n=(a=this.formField).onBlur)===null||n===void 0||n.call(a,e)}},computed:{active:function(){return this.d_value===!0},hasLabel:function(){return q(this.onLabel)&&q(this.offLabel)},label:function(){return this.hasLabel?this.d_value?this.onLabel:this.offLabel:" "},dataP:function(){return j(At({checked:this.active,invalid:this.$invalid},this.size,this.size))}},directives:{ripple:M}},It=["tabindex","disabled","aria-pressed","aria-label","aria-labelledby","data-p-checked","data-p-disabled","data-p"],Ct=["data-p"];function Pt(t,e,n,a,s,l){var d=lt("ripple");return at((f(),m("button",y({type:"button",class:t.cx("root"),tabindex:t.tabindex,disabled:t.disabled,"aria-pressed":t.d_value,onClick:e[0]||(e[0]=function(){return l.onChange&&l.onChange.apply(l,arguments)}),onBlur:e[1]||(e[1]=function(){return l.onBlur&&l.onBlur.apply(l,arguments)})},l.getPTOptions("root"),{"aria-label":t.ariaLabel,"aria-labelledby":t.ariaLabelledby,"data-p-checked":l.active,"data-p-disabled":t.disabled,"data-p":l.dataP}),[i("span",y({class:t.cx("content")},l.getPTOptions("content"),{"data-p":l.dataP}),[P(t.$slots,"default",{},function(){return[P(t.$slots,"icon",{value:t.d_value,class:it(t.cx("icon"))},function(){return[t.onIcon||t.offIcon?(f(),m("span",y({key:0,class:[t.cx("icon"),t.d_value?t.onIcon:t.offIcon]},l.getPTOptions("icon")),null,16)):x("",!0)]}),i("span",y({class:t.cx("label")},l.getPTOptions("label")),S(l.label),17)]})],16,Ct)],16,It)),[[d]])}H.render=Pt;var xt=`
    .p-selectbutton {
        display: inline-flex;
        user-select: none;
        vertical-align: bottom;
        outline-color: transparent;
        border-radius: dt('selectbutton.border.radius');
    }

    .p-selectbutton .p-togglebutton {
        border-radius: 0;
        border-width: 1px 1px 1px 0;
    }

    .p-selectbutton .p-togglebutton:focus-visible {
        position: relative;
        z-index: 1;
    }

    .p-selectbutton .p-togglebutton:first-child {
        border-inline-start-width: 1px;
        border-start-start-radius: dt('selectbutton.border.radius');
        border-end-start-radius: dt('selectbutton.border.radius');
    }

    .p-selectbutton .p-togglebutton:last-child {
        border-start-end-radius: dt('selectbutton.border.radius');
        border-end-end-radius: dt('selectbutton.border.radius');
    }

    .p-selectbutton.p-invalid {
        outline: 1px solid dt('selectbutton.invalid.border.color');
        outline-offset: 0;
    }

    .p-selectbutton-fluid {
        width: 100%;
    }
    
    .p-selectbutton-fluid .p-togglebutton {
        flex: 1 1 0;
    }
`,Ut={root:function(e){var n=e.props,a=e.instance;return["p-selectbutton p-component",{"p-invalid":a.$invalid,"p-selectbutton-fluid":n.fluid}]}},$t=F.extend({name:"selectbutton",style:xt,classes:Ut}),zt={name:"BaseSelectButton",extends:G,props:{options:Array,optionLabel:null,optionValue:null,optionDisabled:null,multiple:Boolean,allowEmpty:{type:Boolean,default:!0},dataKey:null,ariaLabelledby:{type:String,default:null},size:{type:String,default:null},fluid:{type:Boolean,default:null}},style:$t,provide:function(){return{$pcSelectButton:this,$parentInstance:this}}};function Dt(t,e){var n=typeof Symbol<"u"&&t[Symbol.iterator]||t["@@iterator"];if(!n){if(Array.isArray(t)||(n=W(t))||e){n&&(t=n);var a=0,s=function(){};return{s,n:function(){return a>=t.length?{done:!0}:{done:!1,value:t[a++]}},e:function(c){throw c},f:s}}throw new TypeError(`Invalid attempt to iterate non-iterable instance.
In order to be iterable, non-array objects must have a [Symbol.iterator]() method.`)}var l,d=!0,r=!1;return{s:function(){n=n.call(t)},n:function(){var c=n.next();return d=c.done,c},e:function(c){r=!0,l=c},f:function(){try{d||n.return==null||n.return()}finally{if(r)throw l}}}}function Et(t){return _t(t)||Nt(t)||W(t)||Rt()}function Rt(){throw new TypeError(`Invalid attempt to spread non-iterable instance.
In order to be iterable, non-array objects must have a [Symbol.iterator]() method.`)}function W(t,e){if(t){if(typeof t=="string")return $(t,e);var n={}.toString.call(t).slice(8,-1);return n==="Object"&&t.constructor&&(n=t.constructor.name),n==="Map"||n==="Set"?Array.from(t):n==="Arguments"||/^(?:Ui|I)nt(?:8|16|32)(?:Clamped)?Array$/.test(n)?$(t,e):void 0}}function Nt(t){if(typeof Symbol<"u"&&t[Symbol.iterator]!=null||t["@@iterator"]!=null)return Array.from(t)}function _t(t){if(Array.isArray(t))return $(t)}function $(t,e){(e==null||e>t.length)&&(e=t.length);for(var n=0,a=Array(e);n<e;n++)a[n]=t[n];return a}var X={name:"SelectButton",extends:zt,inheritAttrs:!1,emits:["change"],methods:{getOptionLabel:function(e){return this.optionLabel?B(e,this.optionLabel):e},getOptionValue:function(e){return this.optionValue?B(e,this.optionValue):e},getOptionRenderKey:function(e){return this.dataKey?B(e,this.dataKey):this.getOptionLabel(e)},isOptionDisabled:function(e){return this.optionDisabled?B(e,this.optionDisabled):!1},isOptionReadonly:function(e){if(this.allowEmpty)return!1;var n=this.isSelected(e);return this.multiple?n&&this.d_value.length===1:n},onOptionSelect:function(e,n,a){var s=this;if(!(this.disabled||this.isOptionDisabled(n)||this.isOptionReadonly(n))){var l=this.isSelected(n),d=this.getOptionValue(n),r;if(this.multiple)if(l){if(r=this.d_value.filter(function(p){return!I(p,d,s.equalityKey)}),!this.allowEmpty&&r.length===0)return}else r=this.d_value?[].concat(Et(this.d_value),[d]):[d];else{if(l&&!this.allowEmpty)return;r=l?null:d}this.writeValue(r,e),this.$emit("change",{originalEvent:e,value:r})}},isSelected:function(e){var n=!1,a=this.getOptionValue(e);if(this.multiple){if(this.d_value){var s=Dt(this.d_value),l;try{for(s.s();!(l=s.n()).done;){var d=l.value;if(I(d,a,this.equalityKey)){n=!0;break}}}catch(r){s.e(r)}finally{s.f()}}}else n=I(this.d_value,a,this.equalityKey);return n}},computed:{equalityKey:function(){return this.optionValue?null:this.dataKey},dataP:function(){return j({invalid:this.$invalid})}},directives:{ripple:M},components:{ToggleButton:H}},Kt=["aria-labelledby","data-p"];function qt(t,e,n,a,s,l){var d=rt("ToggleButton");return f(),m("div",y({class:t.cx("root"),role:"group","aria-labelledby":t.ariaLabelledby},t.ptmi("root"),{"data-p":l.dataP}),[(f(!0),m(st,null,ut(t.options,function(r,p){return f(),U(d,{key:l.getOptionRenderKey(r),modelValue:l.isSelected(r),onLabel:l.getOptionLabel(r),offLabel:l.getOptionLabel(r),disabled:t.disabled||l.isOptionDisabled(r),unstyled:t.unstyled,size:t.size,readonly:l.isOptionReadonly(r),onChange:function(h){return l.onOptionSelect(h,r,p)},pt:t.ptm("pcToggleButton")},dt({_:2},[t.$slots.option?{name:"default",fn:V(function(){return[P(t.$slots,"option",{option:r,index:p},function(){return[i("span",y({ref_for:!0},t.ptm("pcToggleButton").label),S(l.getOptionLabel(r)),17)]})]}),key:"0"}:void 0]),1032,["modelValue","onLabel","offLabel","disabled","unstyled","size","readonly","onChange","pt"])}),128))],16,Kt)}X.render=qt;const Ft={class:"form-section"},Mt={class:"form-field"},jt={class:"hint"},Gt={class:"form-field"},Ht={key:0,class:"p-error"},Wt={key:1,class:"hint"},Xt={class:"form-field"},Yt={class:"char-count"},Jt={class:"form-field"},Qt={class:"form-section"},Zt={class:"form-field"},te={class:"form-field"},ee={class:"form-field"},ne={class:"form-field"},oe={key:0,class:"form-field"},le=pt({__name:"ApplicationCreateDrawer",emits:["changed"],setup(t,{emit:e}){const n=e,a=b(""),s=b(""),l=b(""),d=b(""),r=b(""),p=b(""),c=b(""),h=b(""),L=b("APPLICATION"),z=C(()=>a.value!==""||s.value!==""),D=b(null),{goToList:Y,replaceToDetail:J}=wt({listPath:"/applications",dirty:z}),Q=[{label:"Application",value:"APPLICATION"},{label:"Integration",value:"INTEGRATION"}],O=b(!1),A=b(null),E=/^[a-z][a-z0-9-]*$/,R=C(()=>!a.value||E.test(a.value)),N=C(()=>a.value&&E.test(a.value)&&s.value.trim().length>0&&s.value.length<=100);async function Z(){if(N.value){O.value=!0,A.value=null;try{const w=await ht.create({code:a.value,name:s.value,description:l.value||void 0,defaultBaseUrl:d.value||void 0,iconUrl:r.value||void 0,website:p.value||void 0,logo:c.value||void 0,logoMimeType:h.value||void 0,type:L.value});bt.success("Success","Application created"),n("changed"),J(w.id)}catch(w){A.value=w instanceof Error?w.message:"Failed to create application"}finally{O.value=!1}}}return(w,o)=>{const tt=X,v=yt,_=vt,et=mt,K=gt,nt=ot;return f(),U(Vt,{ref_key:"drawer",ref:D,title:"Create Application",subtitle:"Add a new application to the platform",dirty:z.value,onClose:o[10]||(o[10]=u=>ct(Y)())},{footer:V(()=>[g(nt,{bordered:!1},{default:V(()=>[g(K,{label:"Cancel",icon:"pi pi-times",severity:"secondary",outlined:"",disabled:O.value,onClick:o[9]||(o[9]=u=>D.value?.close())},null,8,["disabled"]),g(K,{label:"Create Application",icon:"pi pi-check",loading:O.value,disabled:!N.value,onClick:Z},null,8,["loading","disabled"])]),_:1})]),default:V(()=>[i("section",Ft,[o[15]||(o[15]=i("h3",{class:"section-title"},"Application Identity",-1)),i("div",Mt,[o[11]||(o[11]=i("label",null,[T("Type "),i("span",{class:"required"},"*")],-1)),g(tt,{modelValue:L.value,"onUpdate:modelValue":o[0]||(o[0]=u=>L.value=u),options:Q,optionLabel:"label",optionValue:"value"},null,8,["modelValue"]),i("small",jt,S(L.value==="APPLICATION"?"User-facing application that users can log into":"Third-party adapter or connector for integrations"),1)]),i("div",Gt,[o[12]||(o[12]=i("label",null,[T("Code "),i("span",{class:"required"},"*")],-1)),g(v,{modelValue:a.value,"onUpdate:modelValue":o[1]||(o[1]=u=>a.value=u),placeholder:"e.g., operant",class:"full-width",invalid:!!(a.value&&!R.value)},null,8,["modelValue","invalid"]),a.value&&!R.value?(f(),m("small",Ht," Must start with a letter, use only lowercase letters, numbers, and hyphens ")):(f(),m("small",Wt," Unique identifier for the application. Cannot be changed after creation. "))]),i("div",Xt,[o[13]||(o[13]=i("label",null,[T("Name "),i("span",{class:"required"},"*")],-1)),g(v,{modelValue:s.value,"onUpdate:modelValue":o[2]||(o[2]=u=>s.value=u),placeholder:"Human-friendly name",class:"full-width",invalid:s.value.length>100},null,8,["modelValue","invalid"]),i("small",Yt,S(s.value.length)+" / 100",1)]),i("div",Jt,[o[14]||(o[14]=i("label",null,"Description",-1)),g(_,{modelValue:l.value,"onUpdate:modelValue":o[3]||(o[3]=u=>l.value=u),placeholder:"Optional description",rows:3,class:"full-width"},null,8,["modelValue"])])]),i("section",Qt,[o[26]||(o[26]=i("h3",{class:"section-title"},"Configuration",-1)),i("div",Zt,[o[16]||(o[16]=i("label",null,"Default Base URL",-1)),g(v,{modelValue:d.value,"onUpdate:modelValue":o[4]||(o[4]=u=>d.value=u),placeholder:"https://example.com",class:"full-width"},null,8,["modelValue"]),o[17]||(o[17]=i("small",{class:"hint"},"Base URL for API calls to this application",-1))]),i("div",te,[o[18]||(o[18]=i("label",null,"Icon URL",-1)),g(v,{modelValue:r.value,"onUpdate:modelValue":o[5]||(o[5]=u=>r.value=u),placeholder:"https://example.com/icon.png",class:"full-width"},null,8,["modelValue"]),o[19]||(o[19]=i("small",{class:"hint"},"URL to the application's icon image",-1))]),i("div",ee,[o[20]||(o[20]=i("label",null,"Website",-1)),g(v,{modelValue:p.value,"onUpdate:modelValue":o[6]||(o[6]=u=>p.value=u),placeholder:"https://www.example.com",class:"full-width"},null,8,["modelValue"]),o[21]||(o[21]=i("small",{class:"hint"},"Public website URL for this application",-1))]),i("div",ne,[o[22]||(o[22]=i("label",null,"Logo (SVG)",-1)),g(_,{modelValue:c.value,"onUpdate:modelValue":o[7]||(o[7]=u=>c.value=u),placeholder:"Paste SVG content here",rows:4,class:"full-width"},null,8,["modelValue"]),o[23]||(o[23]=i("small",{class:"hint"},"SVG logo content to embed in the platform",-1))]),c.value?(f(),m("div",oe,[o[24]||(o[24]=i("label",null,"Logo MIME Type",-1)),g(v,{modelValue:h.value,"onUpdate:modelValue":o[8]||(o[8]=u=>h.value=u),placeholder:"image/svg+xml",class:"full-width"},null,8,["modelValue"]),o[25]||(o[25]=i("small",{class:"hint"},"MIME type of the logo (e.g., image/svg+xml)",-1))])):x("",!0)]),A.value?(f(),U(et,{key:0,severity:"error",class:"error-message"},{default:V(()=>[T(S(A.value),1)]),_:1})):x("",!0)]),_:1},8,["dirty"])}}}),ge=ft(le,[["__scopeId","data-v-a912a10d"]]);export{ge as default};
