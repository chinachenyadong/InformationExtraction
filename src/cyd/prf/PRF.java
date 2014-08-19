package cyd.prf;

import java.io.*;
import java.util.*;

public class PRF
{

	public static void prf(String src_feature_path, String src_path, String dst_path) throws Exception
	{
		ArrayList<String> type_list = new ArrayList<String>();
		BufferedReader br = new BufferedReader(new FileReader(src_path));
		String line = null;
		int p = 0;
		while ( (line = br.readLine()) != null )
		{
			String[] strs = line.split(" : ");
			if (strs[0].equals("O") == false)
			{
				++p;
			}
			type_list.add(strs[0]);
		}
		br.close();
		
		HashMap<String, String> feat_type_map = new HashMap<String, String>();
		
		int index = 0, correct = 0;
		br = new BufferedReader(new FileReader(src_feature_path));
		while ( (line = br.readLine()) != null )
		{
			String type = type_list.get(index++);
			String[] strs  = line.split(" ");
			StringBuilder sb = new StringBuilder();
			for (int i = 2; i < strs.length-1; ++i)
			{
				sb.append(strs[i] + " ");
			}
			sb.append(strs[strs.length-1]);
			feat_type_map.put(sb.toString(), type);
		}
		br.close();
		
		br = new BufferedReader(new FileReader(dst_path));
		while ( (line = br.readLine()) != null )
		{
			String[] strs  = line.split(" ");
			StringBuilder sb = new StringBuilder();
			for (int i = 4; i < strs.length-1; ++i)
			{
				sb.append(strs[i] + " ");
			}
			sb.append(strs[strs.length-1]);
			String key = sb.toString();
			if (feat_type_map.containsKey(key) == true)
			{
				String type = feat_type_map.get(key);
				if (type.equals(strs[0]) && type.equals("O") == false)
				{
					++correct;
				}
			}
		}
		br.close();
		
		System.out.println((double) correct / p);
	}

	
	
	public static void main(String[] args) throws Exception
	{
		prf("./cyd/trigger_test_feature", "./cyd/trigger_result", "./tmp/decode_result");
		System.exit(0);
	}

}
