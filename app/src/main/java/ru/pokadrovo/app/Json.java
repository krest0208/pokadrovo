package ru.pokadrovo.app;

import java.util.*;

/** Minimal dependency-free JSON codec. Rejects trailing tokens and malformed escapes. */
public final class Json {
    private Json() {}

    public static Object parse(String input) {
        Reader r = new Reader(input);
        Object result = r.value();
        r.space();
        if (r.i != input.length()) throw new IllegalArgumentException("Unexpected trailing JSON");
        return result;
    }
    @SuppressWarnings("unchecked")
    public static Map<String,Object> object(Object value) {
        if (!(value instanceof Map)) throw new IllegalArgumentException("JSON object expected");
        return (Map<String,Object>) value;
    }
    public static List<?> array(Object value) {
        if (!(value instanceof List)) throw new IllegalArgumentException("JSON array expected");
        return (List<?>) value;
    }
    public static String str(Map<String,Object> m,String k,String def) {
        Object o=m.get(k); return o instanceof String?(String)o:def;
    }
    public static double num(Map<String,Object> m,String k,double def) {
        Object o=m.get(k); return o instanceof Number?((Number)o).doubleValue():def;
    }
    public static boolean bool(Map<String,Object> m,String k,boolean def) {
        Object o=m.get(k); return o instanceof Boolean?(Boolean)o:def;
    }
    public static String stringify(Object o) {
        StringBuilder b=new StringBuilder(); append(b,o);return b.toString();
    }
    private static void append(StringBuilder b,Object o) {
        if(o==null){b.append("null");return;}
        if(o instanceof String){
            b.append('"');
            for(char c:((String)o).toCharArray()) {
                switch(c) {case '"':b.append("\\\"");break;case '\\':b.append("\\\\");break;
                    case '\n':b.append("\\n");break;case '\r':b.append("\\r");break;case '\t':b.append("\\t");break;
                    default:if(c<0x20)b.append(String.format(Locale.ROOT,"\\u%04x",(int)c));else b.append(c);
                }
            }
            b.append('"');return;
        }
        if(o instanceof Boolean||o instanceof Number) {
            if(o instanceof Double&&!Double.isFinite((Double)o))throw new IllegalArgumentException("Nonfinite number");
            if(o instanceof Float&&!Float.isFinite((Float)o))throw new IllegalArgumentException("Nonfinite number");
            b.append(o);return;
        }
        if(o instanceof Map){b.append('{');boolean first=true;for(Object e0:((Map<?,?>)o).entrySet()) {
            Map.Entry<?,?> e=(Map.Entry<?,?>)e0;
            if(!first)b.append(',');first=false;append(b,e.getKey().toString());b.append(':');append(b,e.getValue());
        }b.append('}');return;}
        if(o instanceof Iterable){b.append('[');boolean first=true;for(Object item:(Iterable<?>)o){
            if(!first)b.append(',');first=false;append(b,item);
        }b.append(']');return;}
        throw new IllegalArgumentException("Unsupported JSON type "+o.getClass());
    }
    private static final class Reader {
        final String s;int i=0;
        Reader(String s){this.s=Objects.requireNonNull(s);}
        void space(){while(i<s.length()&&Character.isWhitespace(s.charAt(i)))i++;}
        char next(){if(i>=s.length())throw new IllegalArgumentException("Unexpected end");return s.charAt(i++);}
        boolean consume(char c){space();if(i<s.length()&&s.charAt(i)==c){i++;return true;}return false;}
        Object value(){space();char c=next();
            if(c=='"')return quoted();
            if(c=='{'){
                Map<String,Object> o=new LinkedHashMap<>();space();if(consume('}'))return o;
                do{space();if(next()!='"')throw new IllegalArgumentException("Key must be string");
                    String key=quoted();if(!consume(':'))throw new IllegalArgumentException("Missing colon");
                    if(o.containsKey(key))throw new IllegalArgumentException("Duplicate key");
                    o.put(key,value());space();if(consume('}'))return o;
                }while(consume(','));throw new IllegalArgumentException("Missing object separator");
            }
            if(c=='['){List<Object>a=new ArrayList<>();space();if(consume(']'))return a;
                do{a.add(value());space();if(consume(']'))return a;
                }while(consume(','));throw new IllegalArgumentException("Missing array separator");
            }
            if(c=='t'){literal("rue");return true;}
            if(c=='f'){literal("alse");return false;}
            if(c=='n'){literal("ull");return null;}
            if(c=='-'||(c>='0'&&c<='9')){
                int start=i-1;
                while(i<s.length()&&"0123456789.eE+-".indexOf(s.charAt(i))>=0)i++;
                String number=s.substring(start,i);
                if(!number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?"))throw new IllegalArgumentException("Bad number");
                try{double d=Double.parseDouble(number);if(!Double.isFinite(d))throw new NumberFormatException();
                    if(!number.contains(".")&&!number.contains("e")&&!number.contains("E"))return Long.parseLong(number);
                    return d;
                }catch(NumberFormatException e){throw new IllegalArgumentException("Bad number",e);}
            }
            throw new IllegalArgumentException("Unexpected JSON token");
        }
        void literal(String a){for(char ch:a.toCharArray())if(next()!=ch)throw new IllegalArgumentException("Bad literal");}
        String quoted(){StringBuilder b=new StringBuilder();while(true){char c=next();
                if(c=='"')return b.toString();
                if(c<0x20)throw new IllegalArgumentException("Unescaped control char");
                if(c=='\\'){
                    char e=next();switch(e){case '"':case '\\':case '/':b.append(e);break;
                        case 'b':b.append('\b');break;case 'f':b.append('\f');break;
                        case 'n':b.append('\n');break;case 'r':b.append('\r');break;
                        case 't':b.append('\t');break;
                        case 'u':
                            if(i+4>s.length())throw new IllegalArgumentException("Incomplete unicode");
                            try{b.append((char)Integer.parseInt(s.substring(i,i+4),16));}catch(Exception ex){throw new IllegalArgumentException("Bad unicode",ex);}i+=4;break;
                        default:throw new IllegalArgumentException("Bad escape");
                    }
                }else b.append(c);
            }
        }
    }
}
