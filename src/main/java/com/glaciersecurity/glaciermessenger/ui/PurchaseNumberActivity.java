package com.glaciersecurity.glaciermessenger.ui;

import static com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider.getOkHttpClient;

import android.content.DialogInterface;
import android.content.Intent;
import android.os.Bundle;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;


import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.EndPointConfig;
import com.glaciersecurity.glaciermessenger.Endpoints;
import com.glaciersecurity.glaciermessenger.databinding.ActivityPurchaseNumberBinding;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.ui.adapter.NumberListAdapter;
import com.glaciersecurity.glaciermessenger.utils.Log;
import com.glaciersecurity.glaciermessenger.utils.OkHttpClientProvider;

import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.utils.PhoneUtils;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.annotations.SerializedName;
import com.google.gson.reflect.TypeToken;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import androidx.activity.OnBackPressedCallback;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import androidx.databinding.DataBindingUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

public class PurchaseNumberActivity extends XmppActivity  implements AdapterView.OnItemSelectedListener {
    @Override
    protected void refreshUiReal() {

    }

    static final int PURCHASE_NUM_REQUEST = 1;
    private String lastWaitMsg = null;
    private TextView waitTextField = null;
    private AlertDialog waitDialog = null;
    private Boolean numberPurchased = false;
    public ArrayList<PhoneNumberDetails> availablePhoneNumbers;
    private NumberListAdapter numberListAdapter;
    private ActivityPurchaseNumberBinding binding;
    private Account account;

    public static class PhoneNumberDetails{
        @SerializedName("phoneNumber")
        String phoneNumber;

        public String getPhone_number() {
            return PhoneUtils.reformatNumber(phoneNumber);
        }
    }
    private static class PurchaseNumResponse{
        @SerializedName("message")
        String message;
        @SerializedName("data")
        PurchaseData data;
    }
    protected static class PurchaseData {
        @SerializedName("sid")
        public String sid;
        @SerializedName("phoneNumber")
        public String phoneNumber;
    }

    @Override
    public void onBackendConnected() {
        account = xmppConnectionService.getFirstAccount();
    }
    public void getAllPhoneNumberList(String countryCode) {
        String getAvailableNumListUrl =
                Endpoints.base() + EndPointConfig.GET_AVAILABLE_NUM_LIST_URL;
        OkHttpClient client = getOkHttpClient();
        Map<String, String> formFields = new HashMap<>();
        formFields.put("countryCode", countryCode);
        formFields.put("username", account.getUsername());
        formFields.put("organization", account.getOrganization());

        Request request = null;
        try {
            request = OkHttpClientProvider.buildJwtFormRequestBuilder(getAvailableNumListUrl, formFields)
                    .build();
        } catch (IOException e) {
            closeWaitDialog();
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            String responseBody = "{\"message\":\"failure\"}";
            if (response != null && response.body() != null) {
                responseBody = response.body().string();
            }
            Gson gson = new Gson();
            JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonElement element = obj.get("available_phone_numbers");
            if (element == null || element.isJsonNull()) {
                closeWaitDialog();
                return;
            }
            availablePhoneNumbers = gson.fromJson(element, new TypeToken<ArrayList<PhoneNumberDetails>>(){}.getType());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        TextView no_num = findViewById(R.id.no_num);
                        if(availablePhoneNumbers.size() > 0){
                            no_num.setVisibility(View.GONE);
                        }else {
                            no_num.setVisibility(View.VISIBLE);
                        }
                        numberListAdapter.notifyDataSetChanged();
                        closeWaitDialog();

                    } catch (Exception e) {
                        e.printStackTrace();
                        closeWaitDialog();

                    }
                }
            });

        }catch (Exception ex){
            closeWaitDialog();
        }
    }

    public void getPhoneNumberList(String countryCode, String searchString, String areacode) {
        String getAvailableNumListUrl =
                Endpoints.base() + EndPointConfig.GET_AVAILABLE_NUM_LIST_URL;
        OkHttpClient client = getOkHttpClient();
        Map<String, String> formFields = new HashMap<>();
        formFields.put("countryCode", countryCode);
        formFields.put("areaCode", areacode);
        formFields.put("contains", searchString);
        formFields.put("username", account.getUsername());
        formFields.put("organization", account.getOrganization());
        Request request = null;
        try {
            request = OkHttpClientProvider.buildJwtFormRequestBuilder(getAvailableNumListUrl, formFields)
                    .build();
        } catch (IOException e) {
                closeWaitDialog();
                return;

        }
        try (Response response = client.newCall(request).execute()) {
            String responseBody = "{\"message\":\"failure\"}";
            if (response != null && response.body() != null) {
                responseBody = response.body().string();
            }
            Gson gson = new Gson();
            JsonObject obj = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonElement element = obj.get("available_phone_numbers");
            if (element == null || element.isJsonNull()) {
                closeWaitDialog();
                return;
            }
            availablePhoneNumbers = gson.fromJson(element, new TypeToken<ArrayList<PhoneNumberDetails>>(){}.getType());
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    try {
                        TextView no_num = findViewById(R.id.no_num);
                        if(!availablePhoneNumbers.isEmpty()){
                            no_num.setVisibility(View.GONE);
                        }else {
                            no_num.setVisibility(View.VISIBLE);
                        }
                        numberListAdapter.notifyDataSetChanged();
                        closeWaitDialog();

                    } catch (Exception e) {
                        Log.e(Config.LOGTAG, "no available numbers found", e);
                        closeWaitDialog();

                    }
                }
            });

        }catch (Exception ex){
            closeWaitDialog();
        }
    }
    public void OnNumberClick(String number){
        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(PurchaseNumberActivity.this);
        builder.setMessage(getString(R.string.purchase_number_confirmation_message, number));
        builder.setTitle(R.string.purchase_number_confirmation_title);
        builder.setCancelable(true);
        builder.setPositiveButton(R.string.purchase_number_continue, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialogInterface, int i) {
                showWaitDialog("Purchasing number");
                new Thread(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            PurchaseNum(number);
                        } catch (Exception e) {
                            closeWaitDialog();

                        }
                        closeWaitDialog();

                    }
                }).start();
            }
        });
        builder.setNegativeButton(R.string.cancel, null);
        AlertDialog alertDialog = builder.create();
        alertDialog.show();
    }

    private void PurchaseNum(String number){
        String purchaseNumberUrl =
                Endpoints.base() + EndPointConfig.PURCHASE_NUMBER_URL;
        OkHttpClient client = getOkHttpClient();
        Map<String, String> formFields = new HashMap<>();
        formFields.put("purchaseNum", number);
        formFields.put("username", account.getUsername());
        formFields.put("organization", account.getOrganization());
        Request request = null;
        try {
            request = OkHttpClientProvider.buildJwtFormRequestBuilder(purchaseNumberUrl, formFields)
                    .build();
        } catch (IOException e) {
            Log.e(Config.LOGTAG, e.getLocalizedMessage(), e);
            closeWaitDialog();
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        try (Response response = client.newCall(request).execute()) {
            String responseBody = "{\"message\":\"failure\"}";
            if (response != null && response.body() != null) {
                responseBody = response.body().string();
            }
            Gson gson = new Gson();
            String sid = null;
            String phoneNumber = null;
            PurchaseNumResponse purchaseNumResponse = gson.fromJson(responseBody, PurchaseNumResponse.class);
            if(purchaseNumResponse.message != null && purchaseNumResponse.message.equals("success")){
                if (purchaseNumResponse.data != null && purchaseNumResponse.data.sid != null) {
                    sid = purchaseNumResponse.data.sid;
                    phoneNumber = purchaseNumResponse.data.phoneNumber;
                }
                runOnUiThread(() -> {
                    Toast.makeText(PurchaseNumberActivity.this, R.string.purchase_number_added_successfully, Toast.LENGTH_LONG).show();
                    closeWaitDialog();
                    numberPurchased = true;
                });

                final Intent result = new Intent();
                result.putExtra("proxyNum", PhoneUtils.getStripPhoneNumberWithCountryCode(phoneNumber));
                result.putExtra("sid", sid);
                setResult(RESULT_OK, result);
                finish();

            }else{
                runOnUiThread(() -> {
                    Toast.makeText(PurchaseNumberActivity.this, R.string.purchase_number_failed_to_add, Toast.LENGTH_LONG).show();
                    closeWaitDialog();
                    setResult(RESULT_CANCELED);
                    finish();
                });
            }

        }catch (Exception ex){
            Log.e(Config.LOGTAG, ex.getLocalizedMessage(), ex);
            closeWaitDialog();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        this.binding = DataBindingUtil.setContentView(this, R.layout.activity_purchase_number);

        Activities.setStatusAndNavigationBarColors(this, binding.getRoot());
        setSupportActionBar(binding.toolbar);
        configureActionBar(getSupportActionBar());
        List<String> country_codes = Arrays.asList(getResources().getStringArray(R.array.country_codes));
        ArrayAdapter<CharSequence>adapter=ArrayAdapter.createFromResource(this, R.array.country_codes, android.R.layout.simple_spinner_item);
        adapter.setDropDownViewResource(R.layout.dropdown_cc);
        binding.countrycode.setOnItemSelectedListener(this);
        binding.countrycode.setAdapter(adapter);
        binding.countrycode.setSelection(country_codes.indexOf("United States - US"));

        RecyclerView recyclerView = findViewById(R.id.recycler_view);
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        recyclerView.setLayoutManager(layoutManager);
        numberListAdapter = new NumberListAdapter(this);
        recyclerView.setAdapter(numberListAdapter);

        binding.editPurchaseAreaCode.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                boolean handled = false;
                if (actionId == EditorInfo.IME_ACTION_DONE) {
                    submitAreaCode();
                    handled = true;
                }
                return handled;
            }
        });
        ImageView SubmitareaCode = findViewById(R.id.get_area_code_num);
        SubmitareaCode.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                submitAreaCode();
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                finish();
                Intent intent = new Intent(PurchaseNumberActivity.this, PhoneModuleActivity.class);
                intent.putExtra(PhoneModuleActivity.PHONE_MOD_INTENT, PhoneModuleActivity.SMS_FRAG);
                startActivity(intent);
            }
        });
    }

    private void submitAreaCode(){
        String searchString = binding.editPurchaseAreaCode.getText().toString().trim();
        String areaCode = "";

        int sublength;
        try {
            sublength = Math.min(searchString.length(), 3);
                areaCode = searchString.substring(0, sublength);

        } catch (Exception ignored){

        }
        final String ac = areaCode;
        Spinner getcountrycode = findViewById(R.id.countrycode);
        String countryNamecode = getcountrycode.getSelectedItem().toString();
        String[] countrySplitCode = countryNamecode.split("-");
        String countryCode = countrySplitCode[1].trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                getPhoneNumberList(countryCode,searchString, ac);
            }
        }).start();
    }


    public void closeWaitDialog() {
        if (waitDialog != null) {
            waitDialog.dismiss();
            waitDialog = null;
            lastWaitMsg = null;
            waitTextField = null;
        }
    }

    public void showWaitDialog(String message) {
        if (this.isFinishing()) {
            return;
        }
        if (message.equalsIgnoreCase(lastWaitMsg)) {
            return;
        } else if (waitDialog != null && waitTextField != null) {
            waitTextField.setText(message);
            return;
        }
        lastWaitMsg = message;
        LayoutInflater inflater = getLayoutInflater();
        View layout = inflater.inflate(R.layout.dialog_wait, null);
        waitTextField = layout.findViewById(R.id.status_message);
        waitTextField.setText(message);

        AlertDialog.Builder builder = new MaterialAlertDialogBuilder(PurchaseNumberActivity.this);
        builder.setView(layout);
        builder.setCancelable(false);
        builder.setTitle(R.string.purchase_number_please_wait);

        waitDialog = builder.create();
        waitDialog.show();
    }

    @Override
    public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
        showWaitDialog("Searching...");
        Spinner getcountrycode = findViewById(R.id.countrycode);
        String countryNamecode = getcountrycode.getSelectedItem().toString();
        String[] countrySplitCode = countryNamecode.split("-");
        String countryCode = countrySplitCode[1].trim();
        new Thread(new Runnable() {
            @Override
            public void run() {
                getAllPhoneNumberList(countryCode);
            }
        }).start();
    }

    @Override
    public void onNothingSelected(AdapterView<?> adapterView) {
        Toast.makeText(this, R.string.purchase_number_nothing_selected, Toast.LENGTH_SHORT).show();
    }
}
