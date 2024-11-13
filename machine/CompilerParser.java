/* CompilerParser.java */
/* Generated By:JavaCC: Do not edit this line. CompilerParser.java */
package machine;


import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.HashMap;


public class CompilerParser implements CompilerParserConstants {

    private List<Number> byteCode = new ArrayList<>();
    private CodeSegment codeSegment = new CodeSegment();
    private DataSegment dataSegment = new DataSegment();

    public static void main(String[] args) throws ParseException {
        CompilerParser parser = new CompilerParser(System.in);
        parser.Program();
    }

  final public List<Number> Program() throws ParseException {
    EntryPoint();
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case COMMENT:{
      Comment();
      break;
      }
    default:
      jj_la1[0] = jj_gen;
      ;
    }
    jj_consume_token(EOL);
    StatementsOrBlocks();
    jj_consume_token(0);
byteCode.addAll(dataSegment);
        codeSegment.applyOffset( byteCode.size() );
        byteCode.addAll(codeSegment);
        {if ("" != null) return byteCode;}
    throw new Error("Missing return statement in function");
}

  final public void EntryPoint() throws ParseException {Token id, num;
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case IDENTIFIER:{
      id = jj_consume_token(IDENTIFIER);
dataSegment.add(codeSegment.newLabel(id.image));
      break;
      }
    case NUMBER:{
      num = jj_consume_token(NUMBER);
dataSegment.add( new Number(num.image) );
      break;
      }
    default:
      jj_la1[1] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
}

  final public void Comment() throws ParseException {
    jj_consume_token(COMMENT);
}

  final public void StatementsOrBlocks() throws ParseException {
    label_1:
    while (true) {
      switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
      case HALT:
      case NOP:
      case INC:
      case DEC:
      case JNZ:
      case DO:
      case WHILE:
      case IF:
      case IDENTIFIER:
      case COMMENT:
      case EOL:
      case 20:{
        ;
        break;
        }
      default:
        jj_la1[2] = jj_gen;
        break label_1;
      }
      LabeledStatementOrBlock();
    }
}

  final public void LabeledStatementOrBlock() throws ParseException {
    if (jj_2_1(3)) {
      VariableDeclaration();
    } else {
      switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
      case HALT:
      case NOP:
      case INC:
      case DEC:
      case JNZ:
      case DO:
      case WHILE:
      case IF:
      case IDENTIFIER:
      case COMMENT:
      case EOL:
      case 20:{
        label_2:
        while (true) {
          switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
          case IDENTIFIER:{
            ;
            break;
            }
          default:
            jj_la1[3] = jj_gen;
            break label_2;
          }
          LabelDeclaration();
        }
        StatementOrBlock();
        break;
        }
      default:
        jj_la1[4] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
}

  final public void VariableDeclaration() throws ParseException {Token id, rvalue;
    if (jj_2_2(3)) {
      id = jj_consume_token(IDENTIFIER);
      jj_consume_token(COLON);
      rvalue = jj_consume_token(NUMBER);
      switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
      case COMMENT:{
        Comment();
        break;
        }
      default:
        jj_la1[5] = jj_gen;
        ;
      }
      jj_consume_token(EOL);
Number variable = dataSegment.newVariable(id.image, rvalue.image);
          codeSegment.addClear(variable);
          int value = Integer.parseInt(rvalue.image);
          for( int i = 0; i < value; ++i ) {
              codeSegment.addINC(variable);
          }
    } else {
      switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
      case IDENTIFIER:{
        id = jj_consume_token(IDENTIFIER);
        jj_consume_token(COLON);
        rvalue = jj_consume_token(IDENTIFIER);
        switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
        case COMMENT:{
          Comment();
          break;
          }
        default:
          jj_la1[6] = jj_gen;
          ;
        }
        jj_consume_token(EOL);
Number left   = dataSegment.newVariable(id.image, 0);
          Number middle = dataSegment.newVariable(0);
          Number right  = dataSegment.getVariable(rvalue.image);
          codeSegment.addClear(left);
          codeSegment.addClear(middle);
          codeSegment.addINC(right);
          Number cloneLoop = codeSegment.newLabel();
          codeSegment.addINC(left);
          codeSegment.addINC(middle);
          codeSegment.addDEC(right);
          codeSegment.addJNZ(right, cloneLoop);
          codeSegment.addDEC(left);
          Number restoreLoop = codeSegment.newLabel();
          codeSegment.addINC(right);
          codeSegment.addDEC(middle);
          codeSegment.addJNZ(middle, restoreLoop);
          codeSegment.addDEC(right);
        break;
        }
      default:
        jj_la1[7] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
}

  final public void LabelDeclaration() throws ParseException {Token label;
    label = jj_consume_token(IDENTIFIER);
    jj_consume_token(COLON);
codeSegment.placeLabel(label.image);
}

  final public void StatementOrBlock() throws ParseException {
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case 20:{
      Block();
      break;
      }
    case DO:{
      DoWhileBlock();
      break;
      }
    case WHILE:{
      WhileBlock();
      break;
      }
    case IF:{
      IfElseBlock();
      break;
      }
    case HALT:
    case NOP:
    case INC:
    case DEC:
    case JNZ:
    case COMMENT:
    case EOL:{
      Statement();
      break;
      }
    default:
      jj_la1[8] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
}

  final public void Block() throws ParseException {
    jj_consume_token(20);
    StatementsOrBlocks();
    jj_consume_token(21);
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case COMMENT:{
      Comment();
      break;
      }
    default:
      jj_la1[9] = jj_gen;
      ;
    }
    jj_consume_token(EOL);
}

  final public void DoWhileBlock() throws ParseException {Number condition;
    Number target = codeSegment.newLabel();
    jj_consume_token(DO);
    jj_consume_token(20);
    StatementsOrBlocks();
    jj_consume_token(21);
    jj_consume_token(WHILE);
    jj_consume_token(22);
    condition = DoWhileCondition();
    jj_consume_token(23);
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case COMMENT:{
      Comment();
      break;
      }
    default:
      jj_la1[10] = jj_gen;
      ;
    }
    jj_consume_token(EOL);
codeSegment.addJNZ(condition, target);
}

  final public void WhileBlock() throws ParseException {Number condition;
    Number begin = codeSegment.newLabel();
    Number end = codeSegment.newLabel();
    jj_consume_token(WHILE);
    jj_consume_token(22);
    condition = IfCondition();
    jj_consume_token(23);
codeSegment.addJNZ(condition, end);
    jj_consume_token(20);
    StatementsOrBlocks();
    jj_consume_token(21);
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case COMMENT:{
      Comment();
      break;
      }
    default:
      jj_la1[11] = jj_gen;
      ;
    }
    jj_consume_token(EOL);
codeSegment.addJNZ(new Number(0), begin);
        codeSegment.placeLabel(end);
}

  final public void IfElseBlock() throws ParseException {Number condition;
    Number elseLabel = codeSegment.newLabel();
    Number endLabel = codeSegment.newLabel();
    jj_consume_token(IF);
    jj_consume_token(22);
    condition = IfCondition();
    jj_consume_token(23);
codeSegment.addJNZ(condition, elseLabel);
    jj_consume_token(20);
    StatementsOrBlocks();
    jj_consume_token(21);
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case ELSE:{
      jj_consume_token(ELSE);
codeSegment.addJNZ(new Number(0), endLabel);
        codeSegment.placeLabel(elseLabel);
      jj_consume_token(20);
      StatementsOrBlocks();
      jj_consume_token(21);
      break;
      }
    default:
      jj_la1[12] = jj_gen;
      ;
    }
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case COMMENT:{
      Comment();
      break;
      }
    default:
      jj_la1[13] = jj_gen;
      ;
    }
    jj_consume_token(EOL);
codeSegment.placeLabel(endLabel);
}

  final public void Statement() throws ParseException {
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case HALT:
    case NOP:
    case INC:
    case DEC:
    case JNZ:{
      Command();
      break;
      }
    default:
      jj_la1[14] = jj_gen;
      ;
    }
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case COMMENT:{
      Comment();
      break;
      }
    default:
      jj_la1[15] = jj_gen;
      ;
    }
    jj_consume_token(EOL);
}

  final public Number DoWhileCondition() throws ParseException {Number address;
    Token number;
    if (jj_2_3(2)) {
      address = Variable();
      jj_consume_token(NEQ);
      number = jj_consume_token(NUMBER);
int n = Integer.parseInt(number.image);
        if( n == 0 ) {
            {if ("" != null) return address;}
        } else {
            {if ("" != null) return address;}
        }
    } else {
      switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
      case IDENTIFIER:{
        address = Variable();
        jj_consume_token(EQ);
        number = jj_consume_token(NUMBER);
Number target = codeSegment.newLabel();
        Number flag = dataSegment.newVariable(0);
        codeSegment.addClear(flag);
        codeSegment.addJNZ(address, target);
        codeSegment.addINC(flag);
        codeSegment.placeLabel(target);
        {if ("" != null) return flag;}
        break;
        }
      default:
        jj_la1[16] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
}

  final public Number IfCondition() throws ParseException {Number address;
    Token number;
    if (jj_2_4(2)) {
      address = Variable();
      jj_consume_token(NEQ);
      number = jj_consume_token(NUMBER);
Number target = codeSegment.newLabel();
        Number flag = dataSegment.newVariable(0);
        codeSegment.addClear(flag);
        codeSegment.addJNZ(address, target);
        codeSegment.addINC(flag);
        codeSegment.placeLabel(target);
        {if ("" != null) return flag;}
    } else {
      switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
      case IDENTIFIER:{
        address = Variable();
        jj_consume_token(EQ);
        number = jj_consume_token(NUMBER);
{if ("" != null) return address;}
        break;
        }
      default:
        jj_la1[17] = jj_gen;
        jj_consume_token(-1);
        throw new ParseException();
      }
    }
    throw new Error("Missing return statement in function");
}

  final public void Command() throws ParseException {Number arg1, arg2;
    switch ((jj_ntk==-1)?jj_ntk_f():jj_ntk) {
    case HALT:{
      Halt();
      break;
      }
    case NOP:{
      Nop();
      break;
      }
    case INC:{
      Inc();
      break;
      }
    case DEC:{
      Dec();
      break;
      }
    case JNZ:{
      Jnz();
      break;
      }
    default:
      jj_la1[18] = jj_gen;
      jj_consume_token(-1);
      throw new ParseException();
    }
}

  final public Number Variable() throws ParseException {Token id;
    id = jj_consume_token(IDENTIFIER);
{if ("" != null) return dataSegment.getVariable(id.image);}
    throw new Error("Missing return statement in function");
}

  final public void Halt() throws ParseException {
    jj_consume_token(HALT);
codeSegment.add(Number.HALT);
}

  final public void Nop() throws ParseException {
    jj_consume_token(NOP);
codeSegment.add(Number.NOP);
}

  final public void Inc() throws ParseException {Number arg1;
    jj_consume_token(INC);
    arg1 = Variable();
codeSegment.addINC(arg1);
}

  final public void Dec() throws ParseException {Number arg1;
    jj_consume_token(DEC);
    arg1 = Variable();
codeSegment.addDEC(arg1);
}

  final public void Jnz() throws ParseException {Number arg1;
    Token arg2;
    jj_consume_token(JNZ);
    arg1 = Variable();
    arg2 = jj_consume_token(IDENTIFIER);
Number target = codeSegment.getLabel(arg2.image);
        codeSegment.addJNZ(arg1, target);
}

  private boolean jj_2_1(int xla)
 {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return (!jj_3_1()); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(0, xla); }
  }

  private boolean jj_2_2(int xla)
 {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return (!jj_3_2()); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(1, xla); }
  }

  private boolean jj_2_3(int xla)
 {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return (!jj_3_3()); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(2, xla); }
  }

  private boolean jj_2_4(int xla)
 {
    jj_la = xla; jj_lastpos = jj_scanpos = token;
    try { return (!jj_3_4()); }
    catch(LookaheadSuccess ls) { return true; }
    finally { jj_save(3, xla); }
  }

  private boolean jj_3_4()
 {
    if (jj_3R_Variable_254_5_4()) return true;
    if (jj_scan_token(NEQ)) return true;
    return false;
  }

  private boolean jj_3_3()
 {
    if (jj_3R_Variable_254_5_4()) return true;
    if (jj_scan_token(NEQ)) return true;
    return false;
  }

  private boolean jj_3R_Variable_254_5_4()
 {
    if (jj_scan_token(IDENTIFIER)) return true;
    return false;
  }

  private boolean jj_3_1()
 {
    if (jj_3R_VariableDeclaration_97_5_3()) return true;
    return false;
  }

  private boolean jj_3R_VariableDeclaration_106_7_5()
 {
    if (jj_scan_token(IDENTIFIER)) return true;
    if (jj_scan_token(COLON)) return true;
    if (jj_scan_token(IDENTIFIER)) return true;
    return false;
  }

  private boolean jj_3R_VariableDeclaration_97_5_3()
 {
    Token xsp;
    xsp = jj_scanpos;
    if (jj_3_2()) {
    jj_scanpos = xsp;
    if (jj_3R_VariableDeclaration_106_7_5()) return true;
    }
    return false;
  }

  private boolean jj_3_2()
 {
    if (jj_scan_token(IDENTIFIER)) return true;
    if (jj_scan_token(COLON)) return true;
    if (jj_scan_token(NUMBER)) return true;
    return false;
  }

  /** Generated Token Manager. */
  public CompilerParserTokenManager token_source;
  SimpleCharStream jj_input_stream;
  /** Current token. */
  public Token token;
  /** Next token. */
  public Token jj_nt;
  private int jj_ntk;
  private Token jj_scanpos, jj_lastpos;
  private int jj_la;
  private int jj_gen;
  final private int[] jj_la1 = new int[19];
  static private int[] jj_la1_0;
  static {
	   jj_la1_init_0();
	}
	private static void jj_la1_init_0() {
	   jj_la1_0 = new int[] {0x40000,0x6000,0x1c2ff0,0x2000,0x1c2ff0,0x40000,0x40000,0x2000,0x1c0ff0,0x40000,0x40000,0x40000,0x1000,0x40000,0x1f0,0x40000,0x2000,0x2000,0x1f0,};
	}
  final private JJCalls[] jj_2_rtns = new JJCalls[4];
  private boolean jj_rescan = false;
  private int jj_gc = 0;

  /** Constructor with InputStream. */
  public CompilerParser(java.io.InputStream stream) {
	  this(stream, null);
  }
  /** Constructor with InputStream and supplied encoding */
  public CompilerParser(java.io.InputStream stream, String encoding) {
	 try { jj_input_stream = new SimpleCharStream(stream, encoding, 1, 1); } catch(java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
	 token_source = new CompilerParserTokenManager(jj_input_stream);
	 token = new Token();
	 jj_ntk = -1;
	 jj_gen = 0;
	 for (int i = 0; i < 19; i++) jj_la1[i] = -1;
	 for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Reinitialise. */
  public void ReInit(java.io.InputStream stream) {
	  ReInit(stream, null);
  }
  /** Reinitialise. */
  public void ReInit(java.io.InputStream stream, String encoding) {
	 try { jj_input_stream.ReInit(stream, encoding, 1, 1); } catch(java.io.UnsupportedEncodingException e) { throw new RuntimeException(e); }
	 token_source.ReInit(jj_input_stream);
	 token = new Token();
	 jj_ntk = -1;
	 jj_gen = 0;
	 for (int i = 0; i < 19; i++) jj_la1[i] = -1;
	 for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Constructor. */
  public CompilerParser(java.io.Reader stream) {
	 jj_input_stream = new SimpleCharStream(stream, 1, 1);
	 token_source = new CompilerParserTokenManager(jj_input_stream);
	 token = new Token();
	 jj_ntk = -1;
	 jj_gen = 0;
	 for (int i = 0; i < 19; i++) jj_la1[i] = -1;
	 for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Reinitialise. */
  public void ReInit(java.io.Reader stream) {
	if (jj_input_stream == null) {
	   jj_input_stream = new SimpleCharStream(stream, 1, 1);
	} else {
	   jj_input_stream.ReInit(stream, 1, 1);
	}
	if (token_source == null) {
 token_source = new CompilerParserTokenManager(jj_input_stream);
	}

	 token_source.ReInit(jj_input_stream);
	 token = new Token();
	 jj_ntk = -1;
	 jj_gen = 0;
	 for (int i = 0; i < 19; i++) jj_la1[i] = -1;
	 for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Constructor with generated Token Manager. */
  public CompilerParser(CompilerParserTokenManager tm) {
	 token_source = tm;
	 token = new Token();
	 jj_ntk = -1;
	 jj_gen = 0;
	 for (int i = 0; i < 19; i++) jj_la1[i] = -1;
	 for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  /** Reinitialise. */
  public void ReInit(CompilerParserTokenManager tm) {
	 token_source = tm;
	 token = new Token();
	 jj_ntk = -1;
	 jj_gen = 0;
	 for (int i = 0; i < 19; i++) jj_la1[i] = -1;
	 for (int i = 0; i < jj_2_rtns.length; i++) jj_2_rtns[i] = new JJCalls();
  }

  private Token jj_consume_token(int kind) throws ParseException {
	 Token oldToken;
	 if ((oldToken = token).next != null) token = token.next;
	 else token = token.next = token_source.getNextToken();
	 jj_ntk = -1;
	 if (token.kind == kind) {
	   jj_gen++;
	   if (++jj_gc > 100) {
		 jj_gc = 0;
		 for (int i = 0; i < jj_2_rtns.length; i++) {
		   JJCalls c = jj_2_rtns[i];
		   while (c != null) {
			 if (c.gen < jj_gen) c.first = null;
			 c = c.next;
		   }
		 }
	   }
	   return token;
	 }
	 token = oldToken;
	 jj_kind = kind;
	 throw generateParseException();
  }

  @SuppressWarnings("serial")
  static private final class LookaheadSuccess extends java.lang.Error {
    @Override
    public Throwable fillInStackTrace() {
      return this;
    }
  }
  static private final LookaheadSuccess jj_ls = new LookaheadSuccess();
  private boolean jj_scan_token(int kind) {
	 if (jj_scanpos == jj_lastpos) {
	   jj_la--;
	   if (jj_scanpos.next == null) {
		 jj_lastpos = jj_scanpos = jj_scanpos.next = token_source.getNextToken();
	   } else {
		 jj_lastpos = jj_scanpos = jj_scanpos.next;
	   }
	 } else {
	   jj_scanpos = jj_scanpos.next;
	 }
	 if (jj_rescan) {
	   int i = 0; Token tok = token;
	   while (tok != null && tok != jj_scanpos) { i++; tok = tok.next; }
	   if (tok != null) jj_add_error_token(kind, i);
	 }
	 if (jj_scanpos.kind != kind) return true;
	 if (jj_la == 0 && jj_scanpos == jj_lastpos) throw jj_ls;
	 return false;
  }


/** Get the next Token. */
  final public Token getNextToken() {
	 if (token.next != null) token = token.next;
	 else token = token.next = token_source.getNextToken();
	 jj_ntk = -1;
	 jj_gen++;
	 return token;
  }

/** Get the specific Token. */
  final public Token getToken(int index) {
	 Token t = token;
	 for (int i = 0; i < index; i++) {
	   if (t.next != null) t = t.next;
	   else t = t.next = token_source.getNextToken();
	 }
	 return t;
  }

  private int jj_ntk_f() {
	 if ((jj_nt=token.next) == null)
	   return (jj_ntk = (token.next=token_source.getNextToken()).kind);
	 else
	   return (jj_ntk = jj_nt.kind);
  }

  private java.util.List<int[]> jj_expentries = new java.util.ArrayList<int[]>();
  private int[] jj_expentry;
  private int jj_kind = -1;
  private int[] jj_lasttokens = new int[100];
  private int jj_endpos;

  private void jj_add_error_token(int kind, int pos) {
	 if (pos >= 100) {
		return;
	 }

	 if (pos == jj_endpos + 1) {
	   jj_lasttokens[jj_endpos++] = kind;
	 } else if (jj_endpos != 0) {
	   jj_expentry = new int[jj_endpos];

	   for (int i = 0; i < jj_endpos; i++) {
		 jj_expentry[i] = jj_lasttokens[i];
	   }

	   for (int[] oldentry : jj_expentries) {
		 if (oldentry.length == jj_expentry.length) {
		   boolean isMatched = true;

		   for (int i = 0; i < jj_expentry.length; i++) {
			 if (oldentry[i] != jj_expentry[i]) {
			   isMatched = false;
			   break;
			 }

		   }
		   if (isMatched) {
			 jj_expentries.add(jj_expentry);
			 break;
		   }
		 }
	   }

	   if (pos != 0) {
		 jj_lasttokens[(jj_endpos = pos) - 1] = kind;
	   }
	 }
  }

  /** Generate ParseException. */
  public ParseException generateParseException() {
	 jj_expentries.clear();
	 boolean[] la1tokens = new boolean[24];
	 if (jj_kind >= 0) {
	   la1tokens[jj_kind] = true;
	   jj_kind = -1;
	 }
	 for (int i = 0; i < 19; i++) {
	   if (jj_la1[i] == jj_gen) {
		 for (int j = 0; j < 32; j++) {
		   if ((jj_la1_0[i] & (1<<j)) != 0) {
			 la1tokens[j] = true;
		   }
		 }
	   }
	 }
	 for (int i = 0; i < 24; i++) {
	   if (la1tokens[i]) {
		 jj_expentry = new int[1];
		 jj_expentry[0] = i;
		 jj_expentries.add(jj_expentry);
	   }
	 }
	 jj_endpos = 0;
	 jj_rescan_token();
	 jj_add_error_token(0, 0);
	 int[][] exptokseq = new int[jj_expentries.size()][];
	 for (int i = 0; i < jj_expentries.size(); i++) {
	   exptokseq[i] = jj_expentries.get(i);
	 }
	 return new ParseException(token, exptokseq, tokenImage);
  }

  private boolean trace_enabled;

/** Trace enabled. */
  final public boolean trace_enabled() {
	 return trace_enabled;
  }

  /** Enable tracing. */
  final public void enable_tracing() {
  }

  /** Disable tracing. */
  final public void disable_tracing() {
  }

  private void jj_rescan_token() {
	 jj_rescan = true;
	 for (int i = 0; i < 4; i++) {
	   try {
		 JJCalls p = jj_2_rtns[i];

		 do {
		   if (p.gen > jj_gen) {
			 jj_la = p.arg; jj_lastpos = jj_scanpos = p.first;
			 switch (i) {
			   case 0: jj_3_1(); break;
			   case 1: jj_3_2(); break;
			   case 2: jj_3_3(); break;
			   case 3: jj_3_4(); break;
			 }
		   }
		   p = p.next;
		 } while (p != null);

		 } catch(LookaheadSuccess ls) { }
	 }
	 jj_rescan = false;
  }

  private void jj_save(int index, int xla) {
	 JJCalls p = jj_2_rtns[index];
	 while (p.gen > jj_gen) {
	   if (p.next == null) { p = p.next = new JJCalls(); break; }
	   p = p.next;
	 }

	 p.gen = jj_gen + xla - jj_la; 
	 p.first = token;
	 p.arg = xla;
  }

  static final class JJCalls {
	 int gen;
	 Token first;
	 int arg;
	 JJCalls next;
  }

}
